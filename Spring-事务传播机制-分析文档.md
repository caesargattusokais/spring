<!--
 Copyright 2026, Spring Framework Source Analysis
 基于 Spring Framework 7.x 源码
 事务传播机制详细分析
-->

# Spring 事务传播机制详细分析

> 基于 Spring Framework 7.x 源码，逐方法追踪完整调用链

---

## 目录

1. [概述：事务管理的整体架构](#一概述事务管理的整体架构)
2. [TransactionDefinition：传播行为定义](#二transactiondefinition传播行为定义)
3. [AOP 入口：TransactionInterceptor / TransactionAspectSupport](#三aop-入口transactioninterceptor--transactionaspectsupport)
4. [传播机制核心：AbstractPlatformTransactionManager.getTransaction()](#四传播机制核心abstractplatformtransactionmanagergettransaction)
5. [已有事务时的处理：handleExistingTransaction()](#五已有事务时的处理handleexistingtransaction)
6. [无事务时的处理：getTransaction() 后半段](#六无事务时的处理gettransaction-后半段)
7. [事务提交：commit() 完整流程](#七事务提交commit-完整流程)
8. [事务回滚：rollback() 完整流程](#八事务回滚rollback-完整流程)
9. [DataSourceTransactionManager：JDBC 具体实现](#九datasourcetransactionmanagerjdbc-具体实现)
10. [REQUIRES_NEW 挂起与恢复机制](#十requires_new-挂起与恢复机制)
11. [NESTED 嵌套事务：Savepoint 机制](#十一nested-嵌套事务savepoint-机制)
12. [TransactionSynchronizationManager：线程级事务上下文](#十二transactionsynchronizationmanager线程级事务上下文)
13. [七种传播行为对比总结](#十三七种传播行为对比总结)
14. [完整调用链总结](#十四完整调用链总结)

---

## 一、概述：事务管理的整体架构

Spring 事务管理分为**声明式事务**（@Transactional）和**编程式事务**（TransactionTemplate）两种使用方式，底层都依赖 PlatformTransactionManager 接口。

### 1.1 核心组件层次

```
@Transactional (注解)
   ↓
TransactionInterceptor (AOP MethodInterceptor)        ← 拦截被注解的方法
   ↓
TransactionAspectSupport.invokeWithinTransaction()    ← AOP 事务环绕逻辑
   ↓
PlatformTransactionManager.getTransaction(def)        ← 核心：传播行为判断
   ↓
AbstractPlatformTransactionManager (模板方法)          ← 传播行为 + commit/rollback 流程
   ↓
DataSourceTransactionManager (具体实现)                ← JDBC Connection 管理
   ↓
TransactionSynchronizationManager (ThreadLocal)       ← 线程级事务资源管理
```

### 1.2 核心接口关系

```
TransactionDefinition (传播行为 + 隔离级别 + 超时 + 只读)
    └── TransactionAttribute (增加 rollbackOn 规则)
            └── DefaultTransactionAttribute
                    └── RuleBasedTransactionAttribute

PlatformTransactionManager
    └── AbstractPlatformTransactionManager (模板方法模式)
            ├── DataSourceTransactionManager (JDBC)
            └── JtaTransactionManager (JTA)

TransactionStatus (事务状态: isNewTransaction, isRollbackOnly 等)
    └── DefaultTransactionStatus

TransactionSynchronizationManager (ThreadLocal: 当前事务资源)
```

---

## 二、TransactionDefinition：传播行为定义

**源文件**: spring-tx/.../transaction/TransactionDefinition.java

### 2.1 七种传播行为常量

| 常量 | 值 | 语义 |
|------|---|------|
| PROPAGATION_REQUIRED | 0 | 支持当前事务，不存在则新建 |
| PROPAGATION_SUPPORTS | 1 | 支持当前事务，不存在则非事务运行 |
| PROPAGATION_MANDATORY | 2 | 支持当前事务，不存在则抛异常 |
| PROPAGATION_REQUIRES_NEW | 3 | 新建事务，挂起当前事务 |
| PROPAGATION_NOT_SUPPORTED | 4 | 非事务运行，挂起当前事务 |
| PROPAGATION_NEVER | 5 | 非事务运行，存在事务则抛异常 |
| PROPAGATION_NESTED | 6 | 嵌套事务（通过 Savepoint 实现） |

### 2.2 DefaultTransactionDefinition

**源文件**: spring-tx/.../support/DefaultTransactionDefinition.java

```java
public class DefaultTransactionDefinition implements TransactionDefinition {
    private int propagationBehavior = PROPAGATION_REQUIRED;  // 默认 REQUIRED
    private int isolationLevel = ISOLATION_DEFAULT;          // 默认 DEFAULT
    private int timeout = TIMEOUT_DEFAULT;                   // 默认 -1
    private boolean readOnly = false;                        // 默认非只读
    private String name;                                     // 默认 null
}
```

### 2.3 TransactionAttribute — 扩展回滚规则

```java
public interface TransactionAttribute extends TransactionDefinition {
    boolean rollbackOn(Throwable ex);  // 判断异常是否需要回滚
    // 默认: RuntimeException 和 Error 回滚
}
```

---

## 三、AOP 入口：TransactionInterceptor / TransactionAspectSupport

**源文件**: spring-tx/.../interceptor/TransactionAspectSupport.java, TransactionInterceptor.java

### 3.1 TransactionInterceptor.invoke() — AOP 拦截

```java
// TransactionInterceptor.java
public Object invoke(MethodInvocation invocation) throws Throwable {
    Class<?> targetClass = (invocation.getThis() != null
        ? AopUtils.getTargetClass(invocation.getThis()) : null);
    // 委托给父类 TransactionAspectSupport
    return invokeWithinTransaction(invocation.getMethod(), targetClass,
        invocation::proceed);   // callback method executes business logic
}
```

### 3.2 TransactionAspectSupport.invokeWithinTransaction() — 事务环绕

这是 @Transactional 注解生效的核心入口：

```
invokeWithinTransaction(Method method, Class<?> targetClass, InvocationCallback invocation)
│
├─ [1] 获取 TransactionAttribute
│   TransactionAttribute txAttr = tas.getTransactionAttribute(method, targetClass);
│   // AnnotationTransactionAttributeSource 读取 @Transactional 注解
│   // 注解属性 → propagation, isolation, timeout, readOnly, rollbackFor, noRollbackFor
│
├─ [2] 确定 TransactionManager
│   TransactionManager tm = determineTransactionManager(txAttr, targetClass);
│   // @Transactional 的 value/transactionManager 属性指定
│   // 默认查找容器中类型为 PlatformTransactionManager 的 bean
│
├─ [3] 创建/加入事务
│   TransactionInfo txInfo = createTransactionIfNecessary(ptm, txAttr, joinpointIdentification);
│   │
│   └─ createTransactionIfNecessary():
│       if txAttr != null:
│           status = ptm.getTransaction(txAttr);   // ★ 进入传播行为判断
│           // 如果方法名为 null，设为 "ClassName.methodName"
│       return prepareTransactionInfo(tm, txAttr, name, status);
│
├─ [4] 执行业务方法
│   retVal = invocation.proceedWithInvocation();   // 调用目标方法
│
├─ [5] 业务异常处理
│   if ex thrown:
│       completeTransactionAfterThrowing(txInfo, invocation, ex);
│       │
│       ├─ if txAttr.rollbackOn(ex) → tm.rollback(status)
│       └─ else → tm.commit(status)  // 即使异常也可能提交
│       // 注: 如果已有 setRollbackOnly() 标记，commit 也会回滚
│
├─ [6] 正常返回
│   commitTransactionAfterReturning(txInfo);
│   └─ tm.commit(status)
│
└─ [7] finally: 清理 ThreadLocal
    cleanupTransactionInfo(txInfo);
    // 恢复旧的 TransactionInfo 到 ThreadLocal（支持嵌套）
```

### 3.3 TransactionInfo — ThreadLocal 栈式管理

```java
private static final ThreadLocal<TransactionInfo> transactionInfoHolder =
    new NamedThreadLocal<>("Current aspect-driven transaction");

// bindToThread(): 栈式入栈
this.oldTransactionInfo = transactionInfoHolder.get();
transactionInfoHolder.set(this);

// restoreThreadLocalStatus(): 出栈恢复
transactionInfoHolder.set(this.oldTransactionInfo);
```

这保证了嵌套的 @Transactional 方法调用可以正确管理各自的 TransactionInfo。

---

## 四、传播机制核心：AbstractPlatformTransactionManager.getTransaction()

**源文件**: spring-tx/.../support/AbstractPlatformTransactionManager.java

这是整个传播机制的**核心决策树**：

```
getTransaction(TransactionDefinition definition)   ← final 方法，不可覆盖
│
├─ [Step 1] 获取当前事务对象
│   Object transaction = doGetTransaction();
│   // DataSourceTransactionManager: 从 ThreadLocal 获取 ConnectionHolder
│   // 如果当前线程已有 Connection → 事务对象中包含已有连接
│
├─ [Step 2] 判断是否存在事务
│   if isExistingTransaction(transaction):
│       → handleExistingTransaction(def, transaction, debugEnabled)
│   // DataSourceTransactionManager: 检查 ConnectionHolder 是否 active
│
└─ [Step 3] 不存在事务 → 按传播行为处理
    │
    ├─ PROPAGATION_MANDATORY:
    │   → throw IllegalTransactionStateException
    │
    ├─ PROPAGATION_REQUIRED / REQUIRES_NEW / NESTED:
    │   → suspend(null)  // 获取可能存在的 suspendedResources (通常为 null)
    │   → startTransaction(def, transaction, false, ...)
    │       │
    │       └─ startTransaction():
    │           ├─ doBegin(transaction, def)
    │           │   // DataSourceTransactionManager: 获取新 Connection, setAutoCommit(false)
    │           │   // 绑定 ConnectionHolder 到 TransactionSynchronizationManager
    │           ├─ prepareTransactionStatus(def, transaction, true, ...)
    │           │   → new DefaultTransactionStatus(
    │           │       transaction,          // 事务对象
    │           │       true,                // newTransaction = true
    │           │       newSynchronization,  // 是否开启新的同步
    │           │       readOnly, debug, suspendedResources)
    │           │   → 初始化 TransactionSynchronization (如有)
    │           └─ return status
    │
    └─ PROPAGATION_SUPPORTS / NOT_SUPPORTED (无事务情况):
        → prepareTransactionStatus(def, null, true, newSynchronization, ...)
        // 创建"空"事务状态: newTransaction=true, 但 hasTransaction=false
        // 即: 不同步真实事务，但允许注册 Synchronization 回调
```

### 4.1 prepareTransactionStatus() — 构造 DefaultTransactionStatus

```java
protected final DefaultTransactionStatus prepareTransactionStatus(
        TransactionDefinition definition, Object transaction, boolean newTransaction,
        boolean newSynchronization, boolean debug, Object suspendedResources) {

    DefaultTransactionStatus status = new DefaultTransactionStatus(
        transaction, newTransaction, newSynchronization, readOnly, debug, suspendedResources);

    if (newSynchronization) {
        TransactionSynchronizationManager.setActualTransactionActive(status.hasTransaction());
        TransactionSynchronizationManager.setCurrentTransactionName(...);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(...);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(...);
        TransactionSynchronizationManager.initSynchronization();
    }
    return status;
}
```

### 4.2 DefaultTransactionStatus 关键状态字段

| 字段 | 含义 | 不同传播行为的值示例 |
|------|------|-------------------|
| transaction | 底层事务对象 | REQUIRES_NEW: 新 Connection; REQUIRED(有外部): 外部 Connection |
| newTransaction | 本层是否创建了新事务 | REQUIRES_NEW: true; REQUIRED(有外部): false |
| newSynchronization | 本层是否初始化了同步 | 取决于 transactionSynchronization 配置 |
| hasTransaction | 是否有活跃的事务对象 | SUPPORTS(无外部): false |
| suspendedResources | 挂起的外部事务资源 | REQUIRES_NEW: 外部事务的 ConnectionHolder |
| rollbackOnly | 回滚标记 | setRollbackOnly() 后设为 true |

---

## 五、已有事务时的处理：handleExistingTransaction()

**源文件**: AbstractPlatformTransactionManager.handleExistingTransaction()  (private 方法)

这是传播行为最复杂的部分。当 isExistingTransaction(transaction) 返回 true 时：

```
handleExistingTransaction(TransactionDefinition def, Object transaction, boolean debug)
│
├─ [1] PROPAGATION_NEVER:
│   → throw IllegalTransactionStateException
│
├─ [2] PROPAGATION_NOT_SUPPORTED:
│   → Object suspendedResources = suspend(transaction);   // ★ 挂起当前事务
│   → prepareTransactionStatus(def, null, false, newSync, debug, suspendedResources)
│   // newTransaction=false, hasTransaction=false → 非事务运行
│   // 方法执行完后: cleanupAfterCompletion → resume(suspendedResources) 恢复
│
├─ [3] PROPAGATION_REQUIRES_NEW:
│   → SuspendedResourcesHolder suspendedResources = suspend(transaction);  // ★ 挂起
│   → startTransaction(def, transaction, false, debug, suspendedResources);
│       → doBegin() 获取新 Connection, 绑定到 ThreadLocal (替换旧的)
│       → status (newTransaction=true)
│   // 方法执行完后: cleanupAfterCompletion → resume(suspendedResources) 恢复
│
├─ [4] PROPAGATION_NESTED:
│   if !nestedTransactionAllowed:
│       → throw NestedTransactionNotSupportedException
│   if useSavepointForNestedTransaction():
│       → status = prepareTransactionStatus(def, transaction, false, false, debug, null)
│       → status.createAndHoldSavepoint();   // ★ 在现有 Connection 上创建 Savepoint
│       → return status (newTransaction=false, 但有 savepoint)
│   else:
│       → startTransaction(def, transaction, true, debug, null)
│       // useSavepoint=false，在已有事务内调用 doBegin 启动真正的嵌套事务 (JTA)
│
├─ [5] PROPAGATION_REQUIRED / SUPPORTS / MANDATORY:
│   → 参与已有事务
│   ├─ [验证] validateExistingTransaction(def, transaction)
│   │   if validateExistingTransaction == true:
│   │       if def.isReadOnly() 与外层不同 → throw
│   │       if def.getIsolationLevel() 与外层不同 → throw
│   │   // 默认 validateExistingTransaction=false
│   ├─ REQUIRED → prepareTransactionStatus(def, transaction, false, newSync, debug, null)
│   ├─ SUPPORTS → 同上
│   └─ MANDATORY → 同上
│
└─ return status
```

### 5.1 关键差异：newTransaction 字段的作用

| 场景 | newTransaction | commit 行为 | rollback 行为 |
|------|:---:|------|------|
| REQUIRES_NEW | true | doCommit() — 提交新连接 | doRollback() — 回滚新连接 |
| NESTED | false | releaseHeldSavepoint() | rollbackToHeldSavepoint() |
| REQUIRED (有外部) | false | 不执行 doCommit | doSetRollbackOnly() — 标记外部 |
| NOT_SUPPORTED | false | 不执行任何操作 | 不执行任何操作 |

---

## 六、无事务时的处理：getTransaction() 后半段

当 isExistingTransaction() 返回 false 时的处理：

```
无事务时的传播行为处理:
│
├─ PROPAGATION_MANDATORY:
│   → throw IllegalTransactionStateException
│
├─ PROPAGATION_REQUIRED / REQUIRES_NEW / NESTED:
│   → 三者行为完全相同 (都不存在外部事务):
│   suspend(null) → startTransaction(def, transaction, false, ...)
│       → doBegin() 创建新事务
│       → 返回 status (newTransaction=true)
│
└─ PROPAGATION_SUPPORTS / NOT_SUPPORTED / NEVER:
    → prepareTransactionStatus(def, null, true, newSync, ...)
    // 创建空事务状态: hasTransaction=false
    // 方法在非事务环境运行，但可能启用同步 (SYNCHRONIZATION_ALWAYS 模式)
```

---

## 七、事务提交：commit() 完整流程

**源文件**: AbstractPlatformTransactionManager.commit()  (final 方法)

```
commit(TransactionStatus status)
│
├─ [1] 防御性检查
│   if status.isCompleted() → throw IllegalTransactionStateException
│
├─ [2] 局部回滚标记检查
│   if status.isLocalRollbackOnly():
│       → processRollback(status, false)
│       │   ├─ triggerBeforeCompletion
│       │   ├─ if isNewTransaction → doRollback(status)         ★ 回滚新事务
│       │   ├─ elif hasTransaction → doSetRollbackOnly(status)  ★ 标记外部
│       │   ├─ triggerAfterCompletion
│       │   └─ cleanupAfterCompletion
│       return
│
├─ [3] 全局回滚标记检查
│   if 外部协调器标记了 rollback-only && shouldCommitOnGlobalRollbackOnly == false:
│       → processRollback(status, true)
│       → throw UnexpectedRollbackException
│
├─ [4] 正常提交流程
│   ├─ triggerBeforeCommit(status)     // beforeCommit 回调
│   ├─ if isNewTransaction:
│   │   ├─ prepareForCommit(status)    // 模板方法
│   │   └─ doCommit(status)           // ★ Connection.commit()
│   │       └─ 如果失败 → doRollbackOnCommitException
│   ├─ elif hasSavepoint:
│   │   └─ releaseHeldSavepoint()     // ★ NESTED: 释放 savepoint
│   ├─ triggerAfterCommit(status)      // afterCommit 回调
│   ├─ triggerBeforeCompletion(status)
│   ├─ cleanupAfterCompletion(status)
│   │   ├─ clear Synchronization
│   │   ├─ if newTransaction → doCleanupAfterCompletion (释放连接)
│   │   └─ if suspendedResources → resume (恢复外部事务)
│   └─ triggerAfterCompletion(status, COMMITTED)
```

### 7.1 关键决策总结

| 条件 | commit 行为 |
|------|-----------|
| isNewTransaction() == true | 调用 doCommit()，真正提交底层事务 |
| isNewTransaction() == false + hasTransaction() == true | 不调用 doCommit()，仅触发回调 |
| isLocalRollbackOnly() | 走 rollback 流程 |
| 全局 rollback-only | 抛 UnexpectedRollbackException |

---

## 八、事务回滚：rollback() 完整流程

```
rollback(TransactionStatus status)
│
├─ [1] 防御性检查: if completed → throw
│
├─ [2] processRollback(status, false)
│   ├─ triggerBeforeCompletion(status)
│   ├─ if isNewTransaction:
│   │   → doRollback(status)       // ★ Connection.rollback()
│   ├─ elif hasSavepoint:
│   │   → rollbackToHeldSavepoint()  // ★ 回滚到 savepoint
│   └─ elif hasTransaction:
│       → doSetRollbackOnly(status)  // ★ 标记外部事务不可提交
│
├─ [3] triggerAfterCompletion(status, ROLLED_BACK)
│
└─ [4] cleanupAfterCompletion(status)
    ├─ clear Synchronization
    ├─ doCleanupAfterCompletion
    └─ resume suspendedResources (如有)
```

### 8.1 rollback 与 globalRollbackOnParticipationFailure

当内层事务（REQUIRED 参与外部事务）抛出异常回滚时：

```
外层: @Transactional → 开始事务 (newTransaction=true)
  内层: @Transactional(propagation=REQUIRED)
    → handleExistingTransaction:
        status = prepareTransactionStatus(def, tx, false, newSync, debug, null)
        // newTransaction=false!
    → 业务抛出异常 → rollback(status)
        → isNewTransaction=false → doSetRollbackOnly(status)
        // 标记外层的 ConnectionHolder 为 rollbackOnly
  外层继续...
  → commit(status)
    → isNewTransaction=true, isRollbackOnly=true
    → processRollback → doRollback → Connection.rollback()
    → throw UnexpectedRollbackException
```

这就是经典的 "Transaction rolled back because it has been marked as rollback-only" 异常的产生原因。

---

## 九、DataSourceTransactionManager：JDBC 具体实现

**源文件**: spring-jdbc/.../datasource/DataSourceTransactionManager.java

### 9.1 初始化

```java
public DataSourceTransactionManager() {
    setNestedTransactionAllowed(true);  // ★ 默认支持嵌套事务 (Savepoint)
}
```

### 9.2 doGetTransaction() — 获取事务对象

```java
protected Object doGetTransaction() {
    DataSourceTransactionObject txObject = new DataSourceTransactionObject();
    txObject.setSavepointAllowed(isNestedTransactionAllowed());
    // 从 TransactionSynchronizationManager 查询当前线程是否已有连接
    ConnectionHolder conHolder = (ConnectionHolder)
        TransactionSynchronizationManager.getResource(obtainDataSource());
    txObject.setConnectionHolder(conHolder, false);  // newConnectionHolder=false
    return txObject;
}
```

### 9.3 isExistingTransaction() — 判断是否存在事务

```java
protected boolean isExistingTransaction(Object transaction) {
    DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;
    return (txObject.hasConnectionHolder()
        && txObject.getConnectionHolder().isTransactionActive());
}
// ConnectionHolder.isTransactionActive()
// → true 当且仅当 doBegin() 中设置了 setTransactionActive(true)
```

### 9.4 doBegin() — 开始事务

```java
protected void doBegin(Object transaction, TransactionDefinition definition) {
    DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;
    Connection con;

    if (!txObject.hasConnectionHolder() ||
            txObject.getConnectionHolder().isSynchronizedWithTransaction()) {
        // ★ 获取新连接 (首次 或 REQUIRES_NEW 需要独立连接)
        Connection newCon = obtainDataSource().getConnection();
        txObject.setConnectionHolder(new ConnectionHolder(newCon), true);
    }

    con = txObject.getConnectionHolder().getConnection();

    // 设置隔离级别
    DataSourceUtils.prepareConnectionForTransaction(con, definition.getIsolationLevel(), ...);

    // ★ 关键: 关闭自动提交，开启事务
    if (con.getAutoCommit()) {
        txObject.setMustRestoreAutoCommit(true);
        con.setAutoCommit(false);
    }

    txObject.getConnectionHolder().setTransactionActive(true);  // ★ 标记事务开始

    // ★ 绑定连接持有者到线程
    if (txObject.isNewConnectionHolder()) {
        TransactionSynchronizationManager.bindResource(
            obtainDataSource(), txObject.getConnectionHolder());
    }
}
```

### 9.5 doCommit / doRollback / doSetRollbackOnly

```java
protected void doCommit(DefaultTransactionStatus status) {
    Connection con = txObject.getConnectionHolder().getConnection();
    con.commit();  // ★ JDBC commit
}

protected void doRollback(DefaultTransactionStatus status) {
    Connection con = txObject.getConnectionHolder().getConnection();
    con.rollback();  // ★ JDBC rollback
}

protected void doSetRollbackOnly(DefaultTransactionStatus status) {
    txObject.setRollbackOnly();  // → ConnectionHolder.setRollbackOnly()
}
```

### 9.6 doCleanupAfterCompletion() — 清理

```java
protected void doCleanupAfterCompletion(Object transaction) {
    // 从线程解绑
    if (txObject.isNewConnectionHolder()) {
        TransactionSynchronizationManager.unbindResource(obtainDataSource());
    }
    Connection con = txObject.getConnectionHolder().getConnection();
    // 恢复 autoCommit
    if (txObject.isMustRestoreAutoCommit()) {
        con.setAutoCommit(true);
    }
    // 恢复隔离级别
    DataSourceUtils.resetConnectionAfterTransaction(con, ...);
    // 释放连接到连接池
    if (txObject.isNewConnectionHolder()) {
        DataSourceUtils.releaseConnection(con, this.dataSource);
    }
    txObject.getConnectionHolder().clear();
}
```

---

## 十、REQUIRES_NEW 挂起与恢复机制

**源文件**: AbstractPlatformTransactionManager.suspend(), resume()

### 10.1 suspend() — 挂起当前事务

```
suspend(Object transaction)
│
├─ if TransactionSynchronizationManager.isSynchronizationActive():
│   // 1. 挂起所有同步回调
│   List<TransactionSynchronization> suspendedSynchronizations = getSynchronizations();
│   for each sync: sync.suspend();
│   TransactionSynchronizationManager.clearSynchronization();
│
│   // 2. 保存当前事务上下文 (name, readOnly, isolationLevel, wasActive)
│
│   // 3. 挂起底层资源
│   Object suspendedResources = doSuspend(transaction);
│   // DataSourceTransactionManager: 解绑 ConnectionHolder → 返回旧 holder
│
│   // 4. 封装挂起信息
│   return new SuspendedResourcesHolder(
│       suspendedResources, suspendedSynchronizations,
│       name, readOnly, isolationLevel, wasActive);
```

### 10.2 resume() — 恢复被挂起的事务

```
resume(Object transaction, SuspendedResourcesHolder resourcesHolder)
│
├─ [1] 恢复底层资源
│   doResume(transaction, resourcesHolder.suspendedResources);
│   // DataSourceTransactionManager: 重新 bindResource 旧的 ConnectionHolder
│
├─ [2] 恢复同步上下文
│   if suspendedSynchronizations != null:
│       TransactionSynchronizationManager.initSynchronization();
│       for each sync: sync.resume();
│       registerSynchronizations(...);
│       setActualTransactionActive(wasActive);
│       setCurrentTransactionName(name);
│       setCurrentTransactionReadOnly(readOnly);
│       setCurrentTransactionIsolationLevel(...);
```

### 10.3 DataSourceTransactionManager 挂起/恢复实现

```java
// doSuspend: 解绑并返回旧的 ConnectionHolder
protected Object doSuspend(Object transaction) {
    DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;
    txObject.setConnectionHolder(null);
    return TransactionSynchronizationManager.unbindResource(obtainDataSource());
}

// doResume: 重新绑定旧的 ConnectionHolder
protected void doResume(Object transaction, Object suspendedResources) {
    TransactionSynchronizationManager.bindResource(
        obtainDataSource(), suspendedResources);
}
```

### 10.4 REQUIRES_NEW 完整生命周期

```
1. 外层 @Transactional(propagation=REQUIRED)
   → doBegin → 获取 Connection-A → setAutoCommit(false) → bindResource

2. 内层 @Transactional(propagation=REQUIRES_NEW)
   → handleExistingTransaction:
       → suspend → 解绑 Connection-A → 保存到 SuspendedResourcesHolder
       → doBegin → 获取新 Connection-B → bindResource(B)

3. 内层业务执行 → 使用 Connection-B

4. 内层 commit:
   → doCommit → Connection-B.commit()
   → cleanupAfterCompletion → 释放 Connection-B
   → resume → bindResource(Connection-A) ★ 恢复外层事务

5. 外层 commit → Connection-A.commit()
```

---

## 十一、NESTED 嵌套事务：Savepoint 机制

### 11.1 创建 Savepoint

```java
// DefaultTransactionStatus.createAndHoldSavepoint()
public void createAndHoldSavepoint() {
    setSavepoint(getSavepointManager().createSavepoint());
}
// → JdbcTransactionObjectSupport.createSavepoint():
//     Connection.setSavepoint()
```

### 11.2 commit 中的 NESTED 处理

```java
// processCommit()
if (status.isNewTransaction()) {
    doCommit(status);              // REQUIRED/REQUIRES_NEW
} else if (status.hasSavepoint()) {
    status.releaseHeldSavepoint(); // ★ NESTED: 释放 savepoint
}
// else: 参与外部事务，不操作
```

### 11.3 rollback 中的 NESTED 处理

```java
// processRollback()
if (status.isNewTransaction()) {
    doRollback(status);              // REQUIRED/REQUIRES_NEW
} else if (status.hasSavepoint()) {
    status.rollbackToHeldSavepoint(); // ★ NESTED: 回滚到 savepoint
} else if (status.hasTransaction()) {
    doSetRollbackOnly(status);       // REQUIRED 参与外部
}
```

### 11.4 NESTED 事务行为对比

| 操作 | NESTED 行为 | REQUIRED (有外部) 行为 |
|------|-----------|---------------------|
| commit | 释放 savepoint，不真正提交 | 无操作 |
| rollback | 回滚到 savepoint，外部继续 | 标记整个事务不可提交 |
| 外部 commit | 提交整个事务 | — |
| 外部 rollback | 回滚整个事务 | — |

### 11.5 NESTED 生命周期示例

```
1. 外层 @Transactional(propagation=REQUIRED)
   → Connection.setAutoCommit(false)

2. 外层操作: INSERT INTO orders VALUES (1)

3. 方法A() @Transactional(propagation=NESTED)
   → Connection.setSavepoint("SP_1")
   → INSERT INTO items VALUES (1)   [成功]
   → commit → releaseSavepoint (不提交底层)

4. 方法B() @Transactional(propagation=NESTED)
   → Connection.setSavepoint("SP_2")
   → INSERT INTO items VALUES (2)   [失败]
   → rollback → Connection.rollback(SP_2)  ★ 回滚到 SP_2
   // items(1) 和 orders(1) 不受影响!

5. 外层 commit → Connection.commit()
   // 最终: orders(1) + items(1) 提交，items(2) 未提交
```

如果方法B 使用 REQUIRED，结果将完全不同：整个事务被标记为 rollback-only，orders(1) 和 items(1) 也丢失。

**这就是 NESTED 的核心价值：局部回滚，不影响外部事务。**

---

## 十二、TransactionSynchronizationManager：线程级事务上下文

**源文件**: spring-tx/.../support/TransactionSynchronizationManager.java

### 12.1 核心 ThreadLocal 字段

```java
public abstract class TransactionSynchronizationManager {
    // 事务资源: DataSource → ConnectionHolder
    private static final ThreadLocal<Map<Object, Object>> resources = ...;

    // 同步回调列表
    private static final ThreadLocal<List<TransactionSynchronization>> synchronizations = ...;

    // 当前事务名 (Class.method)
    private static final ThreadLocal<String> currentTransactionName = ...;

    // 只读标记
    private static final ThreadLocal<Boolean> currentTransactionReadOnly = ...;

    // 隔离级别
    private static final ThreadLocal<Integer> currentTransactionIsolationLevel = ...;

    // 是否有活跃的物理事务
    private static final ThreadLocal<Boolean> actualTransactionActive = ...;
}
```

### 12.2 资源绑定机制

```java
// 绑定 DataSource → ConnectionHolder
bindResource(Object key, Object value) {
    Map<Object, Object> map = resources.get();
    if (map == null) { map = new HashMap<>(); resources.set(map); }
    map.put(key, value);
}

// 解绑
unbindResource(Object key) {
    Map<Object, Object> map = resources.get();
    if (map != null) {
        Object value = map.remove(key);
        if (map.isEmpty()) resources.remove();
        return value;
    }
    return null;
}

// 查询
getResource(Object key) {
    Map<Object, Object> map = resources.get();
    return (map != null ? map.get(key) : null);
}
```

### 12.3 同步回调生命周期

```
注册回调 (ORM 框架如 Hibernate 注册 Session 的关闭):
registerSynchronization(new TransactionSynchronization() {
    void suspend()          // 事务挂起时
    void resume()           // 事务恢复时
    void flush()            // commit 前刷新
    void beforeCommit()     // commit 前
    void beforeCompletion() // 完成前
    void afterCommit()      // commit 后
    void afterCompletion(int status) // 完成后
});

触发顺序:
suspend → resume → flush → beforeCommit → beforeCompletion
→ (实际 commit/rollback)
→ afterCommit → afterCompletion
```

---

## 十三、七种传播行为对比总结

### 13.1 决策矩阵

| 传播行为 | 无外部事务 | 有外部事务 | 外部事务影响 |
|---------|----------|----------|-----------|
| REQUIRED | 新建事务 | 参与外部 | 共享事务，失败一起回滚 |
| SUPPORTS | 非事务运行 | 参与外部 | 共享事务 |
| MANDATORY | 抛异常 | 参与外部 | 共享事务 |
| REQUIRES_NEW | 新建事务 | 挂起外部 → 新建 | 完全独立，互不影响 |
| NOT_SUPPORTED | 非事务运行 | 挂起外部 → 非事务 | 外部暂停，完成后恢复 |
| NEVER | 非事务运行 | 抛异常 | — |
| NESTED | 新建事务 | 创建 Savepoint | 局部回滚，不影响外部 |

### 13.2 物理连接对比

| 传播行为 | 连接数 | 说明 |
|---------|:-----:|------|
| REQUIRED / SUPPORTS / MANDATORY | 复用 1 个 | 内层复用外层 Connection |
| REQUIRES_NEW | 2 个独立连接 | 内层获取新 Connection |
| NESTED | 复用 1 个 + Savepoint | 同一 Connection 上创建 Savepoint |
| NOT_SUPPORTED / NEVER | 0 个 | 无事务，不使用事务连接 |

### 13.3 内层回滚对外层的影响

| 内层传播行为 | 内层回滚对外层影响 |
|------------|----------------|
| REQUIRED | 致命 — 标记外层 rollback-only → 外层无法提交 |
| REQUIRES_NEW | 无影响 — 独立事务，各自回滚/提交 |
| NESTED | 无影响 — 仅回滚到 Savepoint，外层继续 |
| NOT_SUPPORTED | 无影响 — 内层非事务运行 |

### 13.4 典型使用场景

| 传播行为 | 典型场景 |
|---------|--------|
| REQUIRED | 默认，大多数业务方法 |
| REQUIRES_NEW | 审计日志、消息发送 — 必须独立提交 |
| NESTED | 批量处理中某个子操作可独立失败 |
| SUPPORTS | 只读查询方法 |
| MANDATORY | 必须在事务中调用的方法 |
| NOT_SUPPORTED | 不需要事务的重操作 |
| NEVER | 明确不允许在事务中运行的方法 |

---

## 十四、完整调用链

```
@Transactional(propagation=REQUIRES_NEW)
public void doSomething() { ... }

↓ (AOP 代理拦截)

TransactionInterceptor.invoke(MethodInvocation)
  └─ TransactionAspectSupport.invokeWithinTransaction(method, targetClass, invocation)
      │
      ├─ [1] txAttr = AnnotationTransactionAttributeSource.getTransactionAttribute(method)
      │       → 读取 @Transactional 注解 → RuleBasedTransactionAttribute
      │
      ├─ [2] tm = determineTransactionManager(txAttr, targetClass)
      │
      ├─ [3] status = tm.getTransaction(txAttr)
      │       ╔═══════════════════════════════════════════════════════════════╗
      │       ║  AbstractPlatformTransactionManager.getTransaction()         ║
      │       ║    ├─ doGetTransaction() → 从 ThreadLocal 查连接状态          ║
      │       ║    ├─ isExistingTransaction() → true/false                  ║
      │       ║    ├─ [无事务] → 传播行为判断 → startTransaction/empty       ║
      │       ║    └─ [有事务] → handleExistingTransaction()                 ║
      │       ║        ├─ NEVER → throw                                    ║
      │       ║        ├─ NOT_SUPPORTED → suspend + empty                   ║
      │       ║        ├─ REQUIRES_NEW → suspend + startTransaction        ║
      │       ║        ├─ NESTED → createAndHoldSavepoint                  ║
      │       ║        └─ REQUIRED/SUPPORTS/MANDATORY → participate        ║
      │       ╚═══════════════════════════════════════════════════════════════╝
      │
      ├─ [4] txInfo = prepareTransactionInfo(tm, txAttr, name, status)
      │       → TransactionInfo 绑定到 ThreadLocal
      │
      ├─ [5] retVal = invocation.proceedWithInvocation()  // 执行目标方法
      │
      ├─ [6] commitTransactionAfterReturning(txInfo)
      │       ╔═══════════════════════════════════════════════════════════════╗
      │       ║  AbstractPlatformTransactionManager.commit(status)          ║
      │       ║    ├─ localRollbackOnly → processRollback                   ║
      │       ║    ├─ globalRollbackOnly → UnexpectedRollbackException     ║
      │       ║    ├─ triggerBeforeCommit                                   ║
      │       ║    ├─ if newTransaction → doCommit (Connection.commit)     ║
      │       ║    ├─ if hasSavepoint → releaseHeldSavepoint              ║
      │       ║    ├─ triggerAfterCommit                                    ║
      │       ║    ├─ cleanupAfterCompletion                                ║
      │       ║    │   ├─ clear Synchronization                            ║
      │       ║    │   ├─ doCleanup (释放连接, 恢复 autoCommit)             ║
      │       ║    │   └─ if suspendedResources → resume(old tx)          ║
      │       ║    └─ triggerAfterCompletion                               ║
      │       ╚═══════════════════════════════════════════════════════════════╝
      │
      └─ [7] finally: cleanupTransactionInfo(txInfo) → 恢复 ThreadLocal
```

---

## 附录：关键源文件索引

| 类名 | 文件路径 | 说明 |
|------|---------|------|
| TransactionDefinition | spring-tx/.../transaction/TransactionDefinition.java | 传播行为常量定义 |
| DefaultTransactionDefinition | spring-tx/.../support/DefaultTransactionDefinition.java | 默认事务定义 |
| TransactionInterceptor | spring-tx/.../interceptor/TransactionInterceptor.java | AOP 事务拦截器 |
| TransactionAspectSupport | spring-tx/.../interceptor/TransactionAspectSupport.java | AOP 事务环绕支持 |
| AbstractPlatformTransactionManager | spring-tx/.../support/AbstractPlatformTransactionManager.java | 传播机制核心 |
| DefaultTransactionStatus | spring-tx/.../support/DefaultTransactionStatus.java | 事务状态 + savepoint |
| TransactionSynchronizationManager | spring-tx/.../support/TransactionSynchronizationManager.java | 线程级事务上下文 |
| DataSourceTransactionManager | spring-jdbc/.../datasource/DataSourceTransactionManager.java | JDBC 事务管理 |
| JdbcTransactionObjectSupport | spring-jdbc/.../datasource/JdbcTransactionObjectSupport.java | JDBC Savepoint |

---

> 文档生成日期：2026-06-12
> 源码版本：Spring Framework 7.x
> 基于源码路径：D:\Spring-Framework\
> 配套文档：[Spring-IoC-AOP-分析文档.md](Spring-IoC-AOP-分析文档.md) | [Spring-配置类扫描与解析-分析文档.md](Spring-配置类扫描与解析-分析文档.md)
