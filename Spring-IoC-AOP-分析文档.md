# Spring IoC 与 AOP 源码流程分析

> 基于 Spring Framework 7.x 源码，逐方法追踪完整调用链

---

## 目录

1. [IoC 容器：refresh() 完整流程](#一ioc-容器refresh-完整流程)
2. [Bean 的获取与创建：doGetBean()](#二bean-的获取与创建dogetbean)
3. [Bean 创建细节：doCreateBean()](#三bean-创建细节docreatebean)
4. [依赖注入：populateBean()](#四依赖注入populatebean)
5. [初始化：initializeBean()](#五初始化initializebean)
6. [AOP 代理创建](#六aop-代理创建)
7. [AOP 拦截器链的构建与调用](#七aop-拦截器链的构建与调用)
8. [循环依赖与三级缓存](#八循环依赖与三级缓存)
9. [Spring 7.x 新特性：BeanDefinition 改进](#九spring-7x-新特性beandefinition-改进)
10. [Spring 7.x 新特性：AOT 与 GraalVM Native Image](#十spring-7x-新特性aot-与graalvm-native-image)
11. [并发安全与性能优化](#十一并发安全与性能优化)
12. [异常处理与恢复机制](#十二异常处理与恢复机制)

---

## 一、IoC 容器：refresh() 完整流程

`refresh()` 是 Spring 容器启动的核心入口，定义在：
**`spring-context/.../support/AbstractApplicationContext.java:582`**

### 1.1 整体调用链（12步，一步不落）

```
AbstractApplicationContext.refresh()
│
├─ [步骤1] prepareRefresh()
│     ├─ this.startupDate = System.currentTimeMillis()
│     ├─ this.closed.set(false)
│     ├─ this.active.set(true)
│     ├─ initPropertySources()  // 模板方法，子类可覆写（如 Web 容器初始化 Servlet 相关属性源）
│     └─ getEnvironment().validateRequiredProperties()  // 校验必要属性是否存在
│
├─ [步骤2] ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory()
│     ├─ refreshBeanFactory()  // ★ 抽象方法，子类实现
│     │     ├─ 【XML 版本: AbstractRefreshableApplicationContext.refreshBeanFactory()】
│     │     │     ├─ 如果已有 BeanFactory → destroyBeans() + closeBeanFactory()
│     │     │     ├─ DefaultListableBeanFactory beanFactory = createBeanFactory()
│     │     │     │     └─ new DefaultListableBeanFactory(getInternalParentBeanFactory())
│     │     │     ├─ customizeBeanFactory(beanFactory)  // 设置 allowBeanDefinitionOverriding, allowCircularReferences
│     │     │     └─ loadBeanDefinitions(beanFactory)   // ★ 加载 Bean 定义
│     │     │           └─ 【进入 1.2 节详述】
│     │     │
│     │     └─ 【注解版本: AnnotationConfigApplicationContext】
│     │           └─ 构造函数中已通过 AnnotatedBeanDefinitionReader + ClassPathBeanDefinitionScanner
│     │              完成 BeanDefinition 的注册，这里直接返回已创建的 BeanFactory
│     │
│     └─ return getBeanFactory()  // 返回刚刷新的 DefaultListableBeanFactory
│
├─ [步骤3] prepareBeanFactory(beanFactory)
│     ├─ beanFactory.setBeanClassLoader(getClassLoader())
│     ├─ beanFactory.setBeanExpressionResolver(StandardBeanExpressionResolver)  // SPEL #{...} 解析器
│     ├─ beanFactory.addPropertyEditorRegistrar(ResourceEditorRegistrar)        // 属性编辑器
│     ├─ beanFactory.addBeanPostProcessor(ApplicationContextAwareProcessor)     // ★ 处理 Aware 回调
│     ├─ beanFactory.ignoreDependencyInterface(ResourceLoaderAware.class, ...)  // 标记这些接口不走自动装配
│     ├─ beanFactory.registerResolvableDependency(...)                          // 注册可直接解析的依赖
│     ├─ beanFactory.addBeanPostProcessor(ApplicationListenerDetector)          // ★ 检测 ApplicationListener
│     ├─ 检测 LoadTimeWeaver → 注册 LoadTimeWeaverAwareProcessor
│     └─ 注册 Environment、SystemProperties、SystemEnvironment 为单例
│
├─ [步骤4] postProcessBeanFactory(beanFactory)
│     └─ 空实现（模板方法），子类扩展。如 Web 容器注册 request/session scope
│
├─ [步骤5] invokeBeanFactoryPostProcessors(beanFactory)  // ★★★ 核心步骤
│     └─ PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors())
│           ├─ 【第1轮: 手动注册的 BeanDefinitionRegistryPostProcessor (PriorityOrdered → Ordered → 其余)】
│           │     ├─ 对每个 BFPP 调用 postProcessBeanDefinitionRegistry(registry)
│           │     │     └─ ★ ConfigurationClassPostProcessor 在此处理:
│           │     │           ├─ 解析 @Configuration 类
│           │     │           ├─ 处理 @ComponentScan → 扫描并注册 @Component 等
│           │     │           ├─ 处理 @Import → 导入配置类/ImportSelector/ImportBeanDefinitionRegistrar
│           │     │           ├─ 处理 @Bean 方法 → 注册为 BeanDefinition
│           │     │           └─ 处理 @ImportResource → 加载 XML 配置
│           │     └─ 然后对这些 BFPP 调用 postProcessBeanFactory(beanFactory)
│           │
│           ├─ 【第2轮: 容器中的 BeanDefinitionRegistryPostProcessor (PriorityOrdered → Ordered → 其余)】
│           │     └─ 同上流程：先 postProcessBeanDefinitionRegistry() 再 postProcessBeanFactory()
│           │
│           └─ 【第3轮: 所有 BeanFactoryPostProcessor (PriorityOrdered → Ordered → 其余)】
│                 └─ 只调用 postProcessBeanFactory(beanFactory)
│                       └─ ★ PropertySourcesPlaceholderConfigurer: 解析 ${...} 占位符
│
├─ [步骤6] registerBeanPostProcessors(beanFactory)  // ★★★ 核心步骤
│     └─ PostProcessorRegistrationDelegate.registerBeanPostProcessors(beanFactory, this)
│           ├─ 遍历 beanFactory.getBeanNamesForType(BeanPostProcessor.class)
│           ├─ 按优先级分类: PriorityOrdered → Ordered → 其余 → MergedBeanDefinitionPostProcessor
│           ├─ 对每类: getBean(bppName) 创建实例 → beanFactory.addBeanPostProcessor(bpp)
│           │     └─ ★ 这会触发这些关键 BPP 的实例化:
│           │           ├─ AutowiredAnnotationBeanPostProcessor   (PriorityOrdered) → 处理 @Autowired @Value
│           │           ├─ CommonAnnotationBeanPostProcessor      (PriorityOrdered) → 处理 @Resource @PostConstruct @PreDestroy
│           │           ├─ AnnotationAwareAspectJAutoProxyCreator (Ordered)         → ★ AOP 自动代理创建器
│           │           └─ 等等...
│           └─ 最后单独注册 ApplicationListenerDetector (在所有 BPP 末尾)
│
├─ [步骤7] initMessageSource()
│     ├─ 查找名为 "messageSource" 的 bean
│     ├─ 找到 → 赋给 this.messageSource
│     └─ 没找到 → 创建空的 DelegatingMessageSource，注册为单例
│
├─ [步骤8] initApplicationEventMulticaster()
│     ├─ 查找名为 "applicationEventMulticaster" 的 bean
│     ├─ 找到 → 赋给 this.applicationEventMulticaster
│     └─ 没找到 → new SimpleApplicationEventMulticaster(beanFactory)，注册为单例
│
├─ [步骤9] onRefresh()
│     └─ 空实现（模板方法）。Spring Boot 在此启动嵌入式的 Web Server (Tomcat/Jetty)
│
├─ [步骤10] registerListeners()
│     ├─ 把 this.applicationListeners 中的静态监听器注册到 multicaster
│     ├─ 从容器中获取 ApplicationListener 类型的 bean name
│     └─ 把它们的 name 注册到 multicaster（延迟初始化，用到时才 getBean）
│
├─ [步骤11] finishBeanFactoryInitialization(beanFactory)  // ★★★ 实例化所有单例
│     ├─ beanFactory.prepareSingletonBootstrap()
│     ├─ 初始化 bootstrapExecutor（如果配置了）
│     ├─ 初始化 ConversionService（命名为 "conversionService"）
│     ├─ 注册默认 EmbeddedValueResolver → 用于 @Value("${...}") 解析
│     ├─ 提前初始化 BeanFactoryInitializer 类型的 bean
│     ├─ 提前初始化 LoadTimeWeaverAware 类型的 bean
│     ├─ beanFactory.setTempClassLoader(null)    // 停止使用临时 ClassLoader
│     ├─ beanFactory.freezeConfiguration()       // ★ 冻结配置，不允许再修改 BeanDefinition
│     └─ beanFactory.preInstantiateSingletons()  // ★★★ 实例化所有非懒加载单例
│           └─ 【进入第二章详述】
│
└─ [步骤12] finishRefresh()
      ├─ clearResourceCaches()
      ├─ initLifecycleProcessor()   // 查找 "lifecycleProcessor" bean 或创建 DefaultLifecycleProcessor
      ├─ lifecycleProcessor.onRefresh()  // 启动所有 SmartLifecycle bean
      ├─ publishEvent(ContextRefreshedEvent(this))  // ★ 发布容器刷新完成事件
      └─ 如果 GraalVM Native Image: LiveBeanView.registerApplicationContext(this)
```

### 1.2 BeanDefinition 加载（XML 方式）

从步骤 2 `loadBeanDefinitions(beanFactory)` 展开：

```
AbstractXmlApplicationContext.loadBeanDefinitions(DefaultListableBeanFactory)
  └─ new XmlBeanDefinitionReader(beanFactory)
        ├─ reader.setEnvironment(getEnvironment())
        ├─ reader.setResourceLoader(this)
        └─ reader.loadBeanDefinitions(configLocations)
              │
              └─ XmlBeanDefinitionReader.loadBeanDefinitions(String location)
                    └─ loadBeanDefinitions(Resource[] resources)
                          └─ for each Resource:
                                XmlBeanDefinitionReader.loadBeanDefinitions(Resource)
                                  └─ 包装为 EncodedResource
                                  └─ 尝试从当前线程的 ThreadLocal 获取已加载资源（防循环 import）
                                  └─ new InputSource(resource.getInputStream())
                                  └─ doLoadBeanDefinitions(inputSource, resource)
                                        ├─ Document doc = doLoadDocument(inputSource, resource)
                                        │     └─ DefaultDocumentLoader.loadDocument() → DOM 解析 XML
                                        └─ registerBeanDefinitions(doc, resource)
                                              └─ DefaultBeanDefinitionDocumentReader.registerBeanDefinitions(doc, context)
                                                    └─ doRegisterBeanDefinitions(doc.getDocumentElement())
                                                          │
                                                          ├─ 解析 <beans> 的 profile 属性 → 检查是否激活
                                                          ├─ 【前置处理】preProcessXml(root)  // 空实现，子类扩展
                                                          │
                                                          └─ parseBeanDefinitions(root, delegate)
                                                                │
                                                                └─ for each child node of <beans>:
                                                                      │
                                                                      ├─ 默认命名空间 (http://www.springframework.org/schema/beans):
                                                                      │     ├─ <import> → importBeanDefinitionResource(ele)
                                                                      │     │     └─ 递归 loadBeanDefinitions(resourceLocation)
                                                                      │     ├─ <alias>  → getReaderContext().getRegistry().registerAlias(name, alias)
                                                                      │     ├─ <bean>   → processBeanDefinition(ele, delegate)
                                                                      │     │     └─ delegate.parseBeanDefinitionElement(ele)
                                                                      │     │           ├─ 解析 id 属性
                                                                      │     │           ├─ 解析 name 属性（别名，可用逗号/分号分隔）
                                                                      │     │           ├─ 解析 class / parent 属性
                                                                      │     │           ├─ 解析 scope (singleton/prototype/request/session)
                                                                      │     │           ├─ 解析 lazy-init
                                                                      │     │           ├─ 解析 depends-on
                                                                      │     │           ├─ 解析 autowire (no/byName/byType/constructor)
                                                                      │     │           ├─ 解析 constructor-arg 子元素
                                                                      │     │           ├─ 解析 property 子元素
                                                                      │     │           ├─ 解析 qualifier 子元素
                                                                      │     │           ├─ 解析 lookup-method / replaced-method
                                                                      │     │           ├─ 解析 init-method / destroy-method
                                                                      │     │           ├─ 解析 factory-method / factory-bean
                                                                      │     │           └─ 生成 GenericBeanDefinition 对象
                                                                      │     │     └─ BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, registry)
                                                                      │     │           └─ DefaultListableBeanFactory.registerBeanDefinition(beanName, beanDefinition)
                                                                      │     │                 ├─ 校验 BeanDefinition
                                                                      │     │                 ├─ 检查是否有同名定义（allowBeanDefinitionOverriding）
                                                                      │     │                 ├─ this.beanDefinitionMap.put(beanName, beanDefinition)
                                                                      │     │                 ├─ this.beanDefinitionNames.add(beanName)
                                                                      │     │                 └─ 清除 mergedBeanDefinition cache & 按类型缓存
                                                                      │     └─ <beans>  → 递归 doRegisterBeanDefinitions()
                                                                      │
                                                                      └─ 自定义命名空间 (如 <context:component-scan>, <aop:config>, <tx:annotation-driven>):
                                                                            └─ delegate.parseCustomElement(ele)
                                                                                  └─ 找到对应的 NamespaceHandler
                                                                                  └─ handler.parse(ele, parserContext)
                                                                                        └─ ★ 例如 <context:component-scan>
                                                                                           会注册 ConfigurationClassPostProcessor 为 BFPP
```

### 1.3 BeanDefinition 加载（注解方式）

```
new AnnotationConfigApplicationContext(AppConfig.class)
  ├─ 【构造函数内】
  │     ├─ new AnnotatedBeanDefinitionReader(this)
  │     │     └─ 注册关键 BeanDefinition:
  │     │           ├─ ConfigurationClassPostProcessor              → PriorityOrdered BFPP
  │     │           ├─ AutowiredAnnotationBeanPostProcessor         → PriorityOrdered BPP
  │     │           ├─ CommonAnnotationBeanPostProcessor            → PriorityOrdered BPP
  │     │           ├─ PersistenceAnnotationBeanPostProcessor       → Ordered BPP
  │     │           ├─ EventListenerMethodProcessor                 → Ordered BFPP
  │     │           └─ DefaultEventListenerFactory                  → Ordered
  │     │
  │     ├─ new ClassPathBeanDefinitionScanner(this)
  │     │     └─ 配置默认 include filter: 扫描 @Component (及其派生 @Service, @Repository, @Controller)
  │     │
  │     └─ register(AppConfig.class)
  │           └─ AnnotatedBeanDefinitionReader.registerBean(Class<?>...)
  │                 └─ registerBeanDefinition(AnnotatedGenericBeanDefinition)
  │                       └─ 将 AppConfig 类以 AnnotatedGenericBeanDefinition 注册到 beanDefinitionMap
  │
  └─ refresh()  // 调用上文完整的 refresh 流程
        └─ 在步骤 5 invokeBeanFactoryPostProcessors() 时:
              └─ ★ ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry()
                    ├─ 解析 AppConfig 上的 @ComponentScan("com.example")
                    │     └─ ClassPathBeanDefinitionScanner.doScan("com.example")
                    │           └─ 扫描 classpath 下所有 .class 文件
                    │           └─ 对每个类检查: 是否有 @Component 注解 → 符合条件
                    │           └─ for each candidate:
                    │                 ├─ 生成 ScannedGenericBeanDefinition
                    │                 │     ├─ scope 默认 singleton
                    │                 │     ├─ lazyInit 默认 false
                    │                 │     ├─ autowireMode 默认 AUTOWIRE_NO
                    │                 │     └─ 记录 @Lazy, @Primary, @DependsOn, @Role, @Description 元数据
                    │                 ├─ 处理 @Scope 注解中的 scopeName 和 proxyMode
                    │                 └─ registry.registerBeanDefinition(beanName, beanDefinition)
                    │                       └─ beanDefinitionMap.put(beanName, bd)
                    │
                    ├─ 解析 AppConfig 上的 @Import 注解
                    │     ├─ ImportSelector → 调用 selectImports() 获取更多配置类
                    │     └─ ImportBeanDefinitionRegistrar → 回调 registerBeanDefinitions()
                    │
                    ├─ 解析 AppConfig 上的 @ImportResource 注解
                    │     └─ 加载 XML 中的 BeanDefinition
                    │
                    └─ 解析 AppConfig 中的 @Bean 方法
                          └─ 对每个 @Bean 方法:
                                ├─ 生成 ConfigurationClassBeanDefinition
                                ├─ 设置 factoryMethodName = 方法名
                                ├─ 设置 factoryBeanName = AppConfig 的 beanName
                                └─ registry.registerBeanDefinition(methodName, bd)
```

---

## 二、Bean 的获取与创建：doGetBean()

入口在上文步骤 11 的 `preInstantiateSingletons()`：

### 2.1 preInstantiateSingletons() 完整流程

**文件：`DefaultListableBeanFactory.java:1102`**

```java
public void preInstantiateSingletons() throws BeansException {
    // 1. 获取所有 beanDefinitionNames 的副本（避免迭代中修改）
    List<String> beanNames = new ArrayList<>(this.beanDefinitionNames);

    // 2. 遍历所有 beanName
    for (String beanName : beanNames) {
        RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
        // 只处理: 非抽象 + 单例
        if (!mbd.isAbstract() && mbd.isSingleton()) {
            CompletableFuture<?> future = preInstantiateSingleton(beanName, mbd);
        }
    }

    // 3. 所有单例实例化完成后
    for (String beanName : beanNames) {
        Object singleton = getSingleton(beanName, false);
        // ★ 触发 SmartInitializingSingleton 回调
        if (singleton instanceof SmartInitializingSingleton smart) {
            smart.afterSingletonsInstantiated();
        }
    }
}
```

`preInstantiateSingleton()` 会调用 `getBean(beanName)`，从而进入 `doGetBean()`。

### 2.2 doGetBean() 逐行追踪

**文件：`AbstractBeanFactory.java:249`**

```
getBean(beanName)
  └─ doGetBean(name, requiredType, args, typeCheckOnly)
        │
        ├─ [1] String beanName = transformedBeanName(name)
        │     └─ 去掉 FactoryBean 前缀 "&":
        │           "&myFactoryBean" → "myFactoryBean"
        │           别名也在此解析（递归查找 alias → canonicalName）
        │
        ├─ [2] Object sharedInstance = getSingleton(beanName)
        │     └─ 【进入 DefaultSingletonBeanRegistry.getSingleton(beanName, allowEarlyReference=true) 】
        │           ├─ 从 singletonObjects (一级缓存) 取 → 有则直接返回
        │           ├─ 如果 bean 正在创建中 (singletonsCurrentlyInCreation):
        │           │     ├─ 从 earlySingletonObjects (二级缓存) 取 → 有则返回
        │           │     └─ 从 singletonFactories (三级缓存) 取 → 有则 singletonFactory.getObject() 创建早期引用
        │           │           ├─ 将结果放入 earlySingletonObjects (二级缓存)
        │           │           └─ 从 singletonFactories 中移除
        │           └─ 返回 null (缓存未命中)
        │
        ├─ [3] 如果 sharedInstance != null && args == null:
        │     └─ return getObjectForBeanInstance(sharedInstance, requiredType, name, beanName, null)
        │           └─ 处理 FactoryBean: 如果是 FactoryBean 且 name 不以 "&" 开头:
        │                 ├─ 从 factoryBeanObjectCache 取
        │                 └─ factory.getObject() → doGetObjectFromFactoryBean()
        │
        ├─ [4] 如果 sharedInstance == null (缓存未命中，需要创建):
        │     │
        │     ├─ [4.1] 检查 prototype 循环依赖: isPrototypeCurrentlyInCreation(beanName)
        │     │     如果已经在创建中 → 抛 BeanCurrentlyInCreationException
        │     │
        │     ├─ [4.2] 检查父容器:
        │     │     if (parentBeanFactory != null && !containsBeanDefinition(beanName))
        │     │         → 委托给父容器 → parentBeanFactory.getBean(name)
        │     │
        │     ├─ [4.3] markBeanAsCreated(beanName)
        │     │     └─ 加入 alreadyCreated set，标记此 bean 即将被创建
        │     │
        │     ├─ [4.4] RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName)
        │     │     └─ 【★ 合并 BeanDefinition】
        │     │           从 beanDefinitionMap 获取原始 BeanDefinition
        │     │           如果有 parent 属性 → 递归合并父 BeanDefinition
        │     │           遍历属性: 子覆盖父 → 生成 RootBeanDefinition
        │     │           结果缓存到 mergedBeanDefinitions
        │     │
        │     ├─ [4.5] checkMergedBeanDefinition(mbd, beanName, args)
        │     │     └─ 校验该 bean 确实是 singleton 或 prototype（而非 abstract）
        │     │
        │     ├─ [4.6] 处理 depends-on:
        │     │     String[] dependsOn = mbd.getDependsOn()
        │     │     for each dep: registerDependentBean(dep, beanName) → getBean(dep) // ★ 递归创建依赖
        │     │
        │     ├─ [4.7] ★ 根据 scope 创建:
        │     │     │
        │     │     ├─ 如果是 singleton:
        │     │     │     sharedInstance = getSingleton(beanName, () -> {
        │     │     │         return createBean(beanName, mbd, args);  // ★ 进入第三章
        │     │     │     });
        │     │     │     └─ 【进入 getSingleton(beanName, ObjectFactory) 单例创建模式 】
        │     │     │           ├─ 从 singletonObjects 再次检查（双重检查锁）
        │     │     │           ├─ beforeSingletonCreation(beanName)
        │     │     │           │     └─ singletonsCurrentlyInCreation.add(beanName)
        │     │     │           │     └─ inCreationCheckExclusions.remove(beanName)
        │     │     │           ├─ singletonFactory.getObject()  → ★ 调用 createBean()
        │     │     │           ├─ afterSingletonCreation(beanName)
        │     │     │           │     └─ singletonsCurrentlyInCreation.remove(beanName)
        │     │     │           │     └─ inCreationCheckExclusions.add(beanName)
        │     │     │           ├─ addSingleton(beanName, singletonObject)
        │     │     │           │     └─ singletonObjects.put(beanName, singletonObject)  // ★ 放入一级缓存
        │     │     │           │     └─ singletonFactories.remove(beanName)              // ★ 清除三级缓存
        │     │     │           │     └─ earlySingletonObjects.remove(beanName)           // ★ 清除二级缓存
        │     │     │           │     └─ registeredSingletons.add(beanName)
        │     │     │           └─ return singletonObject
        │     │     │
        │     │     ├─ 如果是 prototype:
        │     │     │     beforePrototypeCreation(beanName)
        │     │     │     │     └─ prototypesCurrentlyInCreation.set(beanName)  // ThreadLocal
        │     │     │     prototypeInstance = createBean(beanName, mbd, args)
        │     │     │     afterPrototypeCreation(beanName)
        │     │     │     │     └─ prototypesCurrentlyInCreation.remove()
        │     │     │
        │     │     └─ 如果是其他 scope (request/session/...):
        │     │           Scope scope = this.scopes.get(scopeName)
        │     │           scope.get(beanName, () -> createBean(beanName, mbd, args))
        │     │
        │     └─ [4.8] return getObjectForBeanInstance(sharedInstance, requiredType, name, beanName, mbd)
        │           └─ 处理 FactoryBean 转调（同上步骤 3）
        │
        └─ 【最终返回 bean 实例】
```

---

## 三、Bean 创建细节：doCreateBean()

**文件：`AbstractAutowireCapableBeanFactory.java:556`**

### 3.1 createBean() 入口

```java
// AbstractAutowireCapableBeanFactory.java:488
protected Object createBean(String beanName, RootBeanDefinition mbd, Object[] args) {
    // [1] 解析 bean class
    Class<?> resolvedClass = resolveBeanClass(mbd, beanName);

    // [2] ★ resolveBeforeInstantiation()
    //     给 InstantiationAwareBeanPostProcessor 机会返回代理（返回非 null 则跳过后续创建）
    Object bean = resolveBeforeInstantiation(beanName, mbd);
    if (bean != null) {
        return bean;  // 直接返回代理，不进入 doCreateBean
    }

    // [3] 实际创建
    Object beanInstance = doCreateBean(beanName, mbd, args);
    return beanInstance;
}
```

`resolveBeforeInstantiation()` 详情：
```
resolveBeforeInstantiation(beanName, mbd)
  ├─ if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors())
  ├─ Class<?> targetType = determineTargetType(beanName, mbd)
  └─ bean = applyBeanPostProcessorsBeforeInstantiation(targetType, beanName)
        ├─ 调用每个 InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation(beanClass, beanName)
        │     └─ ★ AbstractAutoProxyCreator.postProcessBeforeInstantiation():
        │           如果有自定义 TargetSource → 直接创建 AOP 代理返回
        │           (此时目标 bean 尚未实例化，可用于懒代理场景)
        └─ 如果返回了非 null 的 bean:
              └─ bean = applyBeanPostProcessorsAfterInitialization(bean, beanName)
                    └─ 调用所有 BeanPostProcessor.postProcessAfterInitialization()
```

### 3.2 doCreateBean() 完整流程

```java
// AbstractAutowireCapableBeanFactory.java:556
protected Object doCreateBean(String beanName, RootBeanDefinition mbd, Object[] args) {
    // ─────────────── 阶段1: 实例化 ───────────────
    BeanWrapper instanceWrapper = createBeanInstance(beanName, mbd, args);
    Object bean = instanceWrapper.getWrappedInstance();
    Class<?> beanType = instanceWrapper.getWrappedClass();

    // ─────────────── 阶段2: 合并 BD 后处理 ───────────────
    synchronized (mbd.postProcessingLock) {
        if (!mbd.postProcessed) {
            applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
            mbd.markAsPostProcessed();
        }
    }

    // ─────────────── 阶段3: 提前暴露（三级缓存） ───────────────
    boolean earlySingletonExposure = (mbd.isSingleton()
            && this.allowCircularReferences
            && isSingletonCurrentlyInCreation(beanName));
    if (earlySingletonExposure) {
        addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
    }

    // ─────────────── 阶段4: 属性填充 ───────────────
    Object exposedObject = bean;
    populateBean(beanName, mbd, instanceWrapper);

    // ─────────────── 阶段5: 初始化 ───────────────
    exposedObject = initializeBean(beanName, exposedObject, mbd);

    // ─────────────── 阶段6: 循环依赖校验 ───────────────
    if (earlySingletonExposure) {
        Object earlySingletonReference = getSingleton(beanName, false);
        if (earlySingletonReference != null) {
            if (exposedObject == bean) {
                exposedObject = earlySingletonReference;
            }
            else if (!allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
                // ★ 抛异常: bean 被代理了，但其他 bean 注入的是原始版本（不一致）
                // 通常因为 @Async 和 @Transactional 同时使用等场景
            }
        }
    }

    // ─────────────── 阶段7: 注册销毁方法 ───────────────
    registerDisposableBeanIfNecessary(beanName, bean, mbd);

    return exposedObject;
}
```

### 3.3 阶段1 详解：createBeanInstance()

**文件：`AbstractAutowireCapableBeanFactory.java:1175`**

```
createBeanInstance(beanName, mbd, args)
  │
  ├─ [1] Class<?> beanClass = resolveBeanClass(mbd, beanName)  // 确保 class 已加载
  │
  ├─ [2] 如果 beanClass 非 public 且不允许非 public 访问 → 抛异常
  │
  ├─ [3] 如果 mbd.getInstanceSupplier() != null:
  │     └─ return obtainFromSupplier(supplier, beanName, mbd)  // 用户自定义 Supplier
  │
  ├─ [4] 如果 mbd.getFactoryMethodName() != null:
  │     └─ return instantiateUsingFactoryMethod(beanName, mbd, args)
  │           └─ new ConstructorResolver(this).instantiateUsingFactoryMethod(...)
  │                 ├─ 解析 factory-bean / factory-method
  │                 ├─ 如果是静态工厂方法 → 直接用 class 调用
  │                 ├─ 如果是实例工厂方法 → getBean(factoryBeanName) 获取工厂实例
  │                 └─ 反射调用 factoryMethod.invoke(...)
  │
  ├─ [5] 检查是否已解析过构造器（缓存优化）:
  │     if (resolved && autowireNecessary)
  │         → return autowireConstructor(beanName, mbd, null, null)
  │     if (resolved && !autowireNecessary)
  │         → return instantiateBean(beanName, mbd)
  │
  ├─ [6] ★ determineConstructorsFromBeanPostProcessors(beanClass, beanName)
  │     └─ 调用 SmartInstantiationAwareBeanPostProcessor.determineCandidateConstructors()
  │           └─ ★ AutowiredAnnotationBeanPostProcessor:
  │                 扫描类上所有构造器:
  │                   - 有 @Autowired(required=true) → 返回该构造器
  │                   - 有 @Autowired(required=false) → 返回所有 @Autowired 标记的构造器
  │                   - 没有 @Autowired → 返回默认构造器 (无参)
  │                   - Kotlin → 返回带默认参数的主构造器
  │
  ├─ [7] 如果 ctors != null OR autowireMode == AUTOWIRE_CONSTRUCTOR
  │        OR mbd 有 constructorArgumentValues OR args != null:
  │     └─ return autowireConstructor(beanName, mbd, ctors, args)
  │           └─ new ConstructorResolver(this).autowireConstructor(...)
  │                 ├─ 对每个候选构造器:
  │                 │     └─ argsHolder = createArgumentArray(beanName, mbd, resolvedValues, ...)
  │                 │           └─ 解析每个构造器参数:
  │                 │                 ├─ 按类型匹配容器中的 bean
  │                 │                 ├─ 按名称匹配容器中的 bean
  │                 │                 ├─ 解析 @Value 默认值
  │                 │                 └─ 调用 getBean(...) 或 resolveDependency(...)
  │                 ├─ 选择匹配度最高的构造器
  │                 └─ instantiate(beanName, mbd, constructor, args)
  │                       └─ Constructor.newInstance(args)  // ★ 反射创建
  │
  └─ [8] 否则使用无参构造器:
        └─ return instantiateBean(beanName, mbd)
              └─ getInstantiationStrategy().instantiate(mbd, beanName, this)
                    └─ SimpleInstantiationStrategy.instantiate():
                          └─ 如果 lookup-method / replaced-method 存在:
                              └─ CglibSubclassingInstantiationStrategy.instantiate()
                                    └─ CGLIB 创建子类覆盖 lookup-method
                          └─ 否则:
                              └─ BeanUtils.instantiateClass(constructor)
                                    └─ Constructor.newInstance()  // ★ 无参反射创建
```

### 3.4 阶段2 详解：applyMergedBeanDefinitionPostProcessors()

```
applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName)
  └─ for each MergedBeanDefinitionPostProcessor:
        └─ bpp.postProcessMergedBeanDefinition(mbd, beanType, beanName)

        ★ AutowiredAnnotationBeanPostProcessor:
            ├─ 扫描 bean class 的所有字段 → 找 @Autowired @Value @Inject 注解
            │     └─ 构建 InjectionMetadata (包含所有注入点)
            └─ 扫描 bean class 的所有方法 → 找 @Autowired @Value @Inject 注解
                  └─ 构建 InjectionMetadata

        ★ CommonAnnotationBeanPostProcessor:
            ├─ 扫描字段 → 找 @Resource 注解
            │     └─ 构建 InjectionMetadata
            ├─ 扫描方法 → 找 @Resource 注解
            │     └─ 构建 InjectionMetadata
            ├─ 扫描方法 → 找 @PostConstruct 注解 → 记录为 initMethod
            └─ 扫描方法 → 找 @PreDestroy 注解 → 记录为 destroyMethod
```

### 3.5 阶段3 详解：addSingletonFactory() —— 提前暴露

```
addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean))
  └─ singletonFactories.put(beanName, singletonFactory)  // ★ 放入三级缓存

当其他 bean 需要注入当前 bean 时（循环依赖）:
  getSingleton(beanName, true)  → 三级缓存查询
    └─ singletonFactory.getObject()
        └─ getEarlyBeanReference(beanName, mbd, bean)
              └─ for each SmartInstantiationAwareBeanPostProcessor:
                    bpp.getEarlyBeanReference(bean, beanName)
                      └─ ★ AbstractAutoProxyCreator.getEarlyBeanReference(bean, beanName):
                            如果需要 AOP 代理 → 提前创建代理对象
                            this.earlyBeanReferences.put(cacheKey, bean)
                            return wrapIfNecessary(bean, beanName, cacheKey)
```

---

## 四、依赖注入：populateBean()

**文件：`AbstractAutowireCapableBeanFactory.java:1390`**

```
populateBean(beanName, mbd, bw)
  │
  ├─ [1] 如果 bw == null (实例为空):
  │     有 property 值 → 抛异常
  │     无 property 值 → return (直接返回)
  │
  ├─ [2] 如果 bean 是 Record 类（Java 14+）:
  │     有 property 值 → 抛异常（Record 不可变）
  │     无 property 值 → return
  │
  ├─ [3] ★ InstantiationAwareBeanPostProcessor.postProcessAfterInstantiation()
  │     for each InstantiationAwareBeanPostProcessor:
  │         if (!bp.postProcessAfterInstantiation(bean, beanName))
  │             return;  // 返回 false 则停止后续填充
  │
  ├─ [4] 处理 autowire byName / byType:
  │     if (resolvedAutowireMode == AUTOWIRE_BY_NAME)
  │         autowireByName(beanName, mbd, bw, pvs)
  │           └─ 获取所有非简单类型(非基本类型/String/Number/Date等)的 setter
  │           └─ for each property:
  │                 getBean(propertyName) → 递归创建依赖
  │                 pvs.add(propertyName, bean)
  │     if (resolvedAutowireMode == AUTOWIRE_BY_TYPE)
  │         autowireByType(beanName, mbd, bw, pvs)
  │           └─ 获取所有非简单类型的 setter
  │           └─ for each property:
  │                 resolveDependency(descriptor, beanName, autowiredBeanNames, converter)
  │                   └─ 按类型查找候选 bean → 有多个候选则按 @Primary > @Priority > 名称匹配
  │                 pvs.add(propertyName, bean)
  │
  ├─ [5] ★ InstantiationAwareBeanPostProcessor.postProcessProperties()
  │     for each InstantiationAwareBeanPostProcessor:
  │         PropertyValues pvsToUse = bp.postProcessProperties(pvs, bean, beanName)
  │
  │         ★ AutowiredAnnotationBeanPostProcessor.postProcessProperties():
  │             ├─ 获取步骤3.4中缓存的 InjectionMetadata
  │             ├─ metadata.inject(bean, beanName, pvs)
  │             │     └─ for each injectionPoint (field/method):
  │             │           ├─ 如果是 @Autowired field:
  │             │           │     └─ resolveDependency(descriptor, beanName, ...)
  │             │           │           └─ DefaultListableBeanFactory.resolveDependency():
  │             │           │                 ├─ 获取 @Qualifier 指定的限定名
  │             │           │                 ├─ findAutowireCandidates(beanName, type, descriptor)
  │             │           │                 │     └─ 从容器中查找匹配类型的 bean
  │             │           │                 │     └─ 处理 @Qualifier 过滤
  │             │           │                 │     └─ 处理泛型匹配 (ResolvableType)
  │             │           │                 ├─ 如果多个候选: 按 @Primary > @Priority > 名称匹配
  │             │           │                 │     无候选 → 检查 required 属性
  │             │           │                 │        required=true → 抛 NoSuchBeanDefinitionException
  │             │           │                 │        required=false → 跳过
  │             │           │                 └─ getBean(candidateName) → ★ 递归创建依赖 bean
  │             │           │                       └─ 再次进入 doGetBean() → createBean() → ...
  │             │           │
  │             │           ├─ 如果是 @Autowired method (setter):
  │             │           │     └─ 解析每个参数 → resolveDependency() → getBean()
  │             │           │     └─ method.invoke(bean, resolvedArgs)
  │             │           │
  │             │           └─ 如果是 @Value field/method:
  │             │                 └─ resolveStringValue(valueExpression)
  │             │                       └─ 解析 ${...} / #{...}
  │             │
  │             └─ 返回 pvs
  │
  │         ★ CommonAnnotationBeanPostProcessor.postProcessProperties():
  │             ├─ 获取 @Resource 的 InjectionMetadata
  │             └─ metadata.inject(bean, beanName, pvs)
  │                   └─ for each @Resource field/method:
  │                         ├─ 获取 @Resource 的 name 属性（如果有指定 → 按名称查找）
  │                         ├─ 没有 name → 按字段名/属性名查找
  │                         ├─ 按名称找不到 → 按类型查找
  │                         └─ getBean(resolvedName) → ★ 递归创建
  │
  ├─ [6] checkDependencies(beanName, mbd, filteredPds, pvs)  // 依赖检查（已废弃）
  │
  └─ [7] applyPropertyValues(beanName, mbd, bw, pvs)
        └─ 应用 XML 中配置的 <property> 值
              ├─ 对每个 PropertyValue:
              │     ├─ 如果是 ref → resolveReference(argName, ref)
              │     │     └─ getBean(ref.getBeanName()) → ★ 递归创建
              │     ├─ 如果是 value → resolveValue(argName, value)
              │     │     └─ TypeConverter 类型转换 (String → int, Date, etc.)
              │     ├─ 如果是 list/array/set/map → 递归解析每个元素
              │     └─ 如果是 inner bean → createBean(anonymousBeanName, mbd)
              └─ bw.setPropertyValues(pvs)  // 通过 BeanWrapper 调用 setter 方法
```

---

## 五、初始化：initializeBean()

**文件：`AbstractAutowireCapableBeanFactory.java:1797`**

```java
protected Object initializeBean(String beanName, Object bean, RootBeanDefinition mbd) {
    // ────── 5.1 Aware 方法回调 ──────
    invokeAwareMethods(beanName, bean);

    // ────── 5.2 BeanPostProcessor 前置处理 ──────
    Object wrappedBean = bean;
    if (mbd == null || !mbd.isSynthetic()) {
        wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
    }

    // ────── 5.3 调用 init 方法 ──────
    invokeInitMethods(beanName, wrappedBean, mbd);

    // ────── 5.4 BeanPostProcessor 后置处理 ──────
    if (mbd == null || !mbd.isSynthetic()) {
        wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
    }

    return wrappedBean;
}
```

### 5.1 invokeAwareMethods()

```
invokeAwareMethods(beanName, bean)
  ├─ if (bean instanceof BeanNameAware)
  │     → beanNameAware.setBeanName(beanName)
  ├─ if (bean instanceof BeanClassLoaderAware)
  │     → beanClassLoaderAware.setBeanClassLoader(getBeanClassLoader())
  └─ if (bean instanceof BeanFactoryAware)
        → beanFactoryAware.setBeanFactory(this)
```

注意：`ApplicationContextAware` 不在此处理，而是由 **`ApplicationContextAwareProcessor`**（在步骤3 prepareBeanFactory 时注册的 BPP）在前置处理阶段处理：

```
ApplicationContextAwareProcessor.postProcessBeforeInitialization(bean, beanName)
  └─ if (bean instanceof EnvironmentAware)
       → environmentAware.setEnvironment(this.applicationContext.getEnvironment())
  └─ if (bean instanceof EmbeddedValueResolverAware)
       → resolverAware.setEmbeddedValueResolver(this.embeddedValueResolver)
  └─ if (bean instanceof ResourceLoaderAware)
       → resourceLoaderAware.setResourceLoader(this.applicationContext)
  └─ if (bean instanceof ApplicationEventPublisherAware)
       → publisherAware.setApplicationEventPublisher(this.applicationContext)
  └─ if (bean instanceof MessageSourceAware)
       → messageSourceAware.setMessageSource(this.applicationContext)
  └─ if (bean instanceof ApplicationContextAware)
       → contextAware.setApplicationContext(this.applicationContext)
```

### 5.2 applyBeanPostProcessorsBeforeInitialization()

```
applyBeanPostProcessorsBeforeInitialization(bean, beanName)
  └─ for each BeanPostProcessor (按注册顺序):
        └─ Object result = bpp.postProcessBeforeInitialization(bean, beanName)
              if (result == null) return bean;  // BPP 终止了后续处理

        关键 BPP:
        ├─ CommonAnnotationBeanPostProcessor.postProcessBeforeInitialization()
        │     └─ 调用 @PostConstruct 标记的方法
        │           └─ metadata.invokeInitMethods(bean, beanName)
        │                 └─ initMethod.invoke(bean)
        │
        ├─ InitDestroyAnnotationBeanPostProcessor.postProcessBeforeInitialization()
        │     └─ 处理 @PostConstruct (CommonAnnotationBeanPostProcessor 的父类逻辑)
        │
        └─ ApplicationContextAwareProcessor
              └─ 处理上述的 ApplicationContextAware 系列接口
```

### 5.3 invokeInitMethods()

```
invokeInitMethods(beanName, bean, mbd)
  ├─ if (bean instanceof InitializingBean)
  │     → ((InitializingBean) bean).afterPropertiesSet()
  │
  └─ if (mbd.getInitMethodName() != null)
        ├─ 反射获取 initMethod
        └─ initMethod.invoke(bean)
        // 这是 XML <bean init-method="..."> 或 @Bean(initMethod="...") 指定的方法
```

### 5.4 applyBeanPostProcessorsAfterInitialization()

```
applyBeanPostProcessorsAfterInitialization(bean, beanName)
  └─ for each BeanPostProcessor (按注册顺序):
        └─ Object result = bpp.postProcessAfterInitialization(bean, beanName)
              if (result == null) return bean;  // BPP 终止了后续处理

        ★★★ 最关键的 BPP → 进入第六章 AOP 代理创建 ★★★
```

---

## 六、AOP 代理创建

AOP 代理在 `applyBeanPostProcessorsAfterInitialization()` 阶段，由 `AbstractAutoProxyCreator` 创建。

### 6.1 入口：postProcessAfterInitialization()

**文件：`AbstractAutoProxyCreator.java:285`**

```java
public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean != null) {
        Object cacheKey = getCacheKey(bean.getClass(), beanName);
        // ★ 如果之前 getEarlyBeanReference 返回过同一个 bean (循环依赖场景):
        //   说明该 bean 已经被作为早期引用处理过，不再重复代理
        if (this.earlyBeanReferences.remove(cacheKey) != bean) {
            return wrapIfNecessary(bean, beanName, cacheKey);  // ★ 进入核心逻辑
        }
    }
    return bean;
}
```

### 6.2 wrapIfNecessary() —— 判断是否需要代理

**文件：`AbstractAutoProxyCreator.java:321`**

```
wrapIfNecessary(bean, beanName, cacheKey)
  │
  ├─ [1] 如果 beanName 在 targetSourcedBeans 中:
  │     └─ return bean  // 已经在 postProcessBeforeInstantiation 中代理过了
  │
  ├─ [2] 如果 advisedBeans 缓存中标记为 FALSE (明确不需要代理):
  │     └─ return bean
  │
  ├─ [3] ★ 检查是否是基础设施类:
  │     if (isInfrastructureClass(bean.getClass()) || shouldSkip(bean.getClass(), beanName))
  │         ├─ isInfrastructureClass():
  │         │     ├─ Advice.class.isAssignableFrom(beanClass)
  │         │     ├─ Pointcut.class.isAssignableFrom(beanClass)
  │         │     └─ Advisor.class.isAssignableFrom(beanClass)
  │         │     └─ AopInfrastructureBean.class.isAssignableFrom(beanClass)
  │         └─ AnnotationAwareAspectJAutoProxyCreator 额外检查:
  │               └─ aspectJAdvisorFactory.isAspect(beanClass) → 是否为 @Aspect 类
  │     └─ 是基础设施类 → advisedBeans.put(cacheKey, FALSE) + return bean
  │
  ├─ [4] ★ 获取匹配的 Advisor:
  │     Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null)
  │     └─ 【进入 6.3 节详述】
  │
  ├─ [5] 如果没有匹配的 Advisor (DO_NOT_PROXY):
  │     └─ advisedBeans.put(cacheKey, FALSE)
  │     └─ return bean  // 原始 bean，不代理
  │
  └─ [6] ★ 创建代理:
        advisedBeans.put(cacheKey, TRUE)
        Object proxy = createProxy(beanClass, beanName, specificInterceptors,
                                    new SingletonTargetSource(bean))
        proxyTypes.put(cacheKey, proxy.getClass())
        return proxy  // ★ 返回 AOP 代理对象
```

### 6.3 getAdvicesAndAdvisorsForBean() —— 匹配切面

**文件：`AbstractAdvisorAutoProxyCreator.java:76`**

```
getAdvicesAndAdvisorsForBean(beanClass, beanName, targetSource)
  └─ findEligibleAdvisors(beanClass, beanName)
        │
        ├─ [1] findCandidateAdvisors()  // ★ 获取所有候选 Advisor
        │     ├─ (父类) AbstractAdvisorAutoProxyCreator.findCandidateAdvisors():
        │     │     └─ this.advisorRetrievalHelper.findAdvisorBeans()
        │     │           └─ 从容器中取出所有 Advisor 类型的 bean:
        │     │                 ├─ getBeanNamesForType(Advisor.class, true, false)
        │     │                 ├─ for each advisorBeanName:
        │     │                 │     ├─ 检查 isEligibleBean(advisorName) // 前缀过滤
        │     │                 │     └─ getBean(advisorName)  → ★ 创建 Advisor bean
        │     │                 └─ 返回 List<Advisor>
        │     │
        │     └─ (子类) AnnotationAwareAspectJAutoProxyCreator.findCandidateAdvisors():
        │           ├─ super.findCandidateAdvisors()  // 先找常规 Advisor
        │           └─ this.aspectJAdvisorsBuilder.buildAspectJAdvisors()
        │                 └─ ★ 解析 @Aspect 注解
        │                       ├─ for each bean in beanFactory:
        │                       │     └─ 检查类是否有 @Aspect 注解 (aspectJAdvisorFactory.isAspect(beanType))
        │                       │
        │                       └─ for each @Aspect bean:
        │                             └─ getAdvisors(aspectMetadata)
        │                                   └─ for each method in aspect class:
        │                                         ├─ 找 Around → @Around 注解 → 创建 AspectJAroundAdvice
        │                                         ├─ 找 Before → @Before 注解 → 创建 AspectJMethodBeforeAdvice
        │                                         ├─ 找 After    → @After 注解 → 创建 AspectJAfterAdvice
        │                                         ├─ 找 AfterReturning → @AfterReturning → AspectJAfterReturningAdvice
        │                                         ├─ 找 AfterThrowing  → @AfterThrowing  → AspectJAfterThrowingAdvice
        │                                         └─ 找 Pointcut → @Pointcut (供上述引用，不单独生成 Advice)
        │                                         │
        │                                         └─ 对每个 advice method:
        │                                               ├─ 解析切入点表达式 (如 "execution(* com.example..*.*(..))")
        │                                               │     └─ AspectJExpressionPointcut
        │                                               │           ├─ 编译 AspectJ 表达式
        │                                               │           ├─ 设置 PointcutExpression
        │                                               │           └─ 此 Pointcut 实现了 ClassFilter + MethodMatcher
        │                                               │
        │                                               └─ new InstantiationModelAwarePointcutAdvisorImpl(
        │                                                     expressionPointcut, candidateAdviceMethod, factory, aspectFactory, ...)
        │                                                     └─ 这是每个 @Aspect advice 对应的 Advisor
        │                                                        内含: Pointcut + Advice
        │
        ├─ [2] findAdvisorsThatCanApply(candidateAdvisors, beanClass, beanName)
        │     └─ AopUtils.findAdvisorsThatCanApply(candidateAdvisors, beanClass)
        │           └─ for each advisor:
        │                 └─ if (advisor instanceof PointcutAdvisor pointcutAdvisor):
        │                       ├─ ClassFilter match = pointcutAdvisor.getPointcut().getClassFilter()
        │                       ├─ if (match.matches(beanClass))  // ★ 类级别匹配
        │                       │     └─ eligibleAdvice.add(advisor)
        │                       │     // 注意: 这里只做类级别过滤，方法级别匹配推迟到方法调用时
        │                       │
        │                       └─ 如果是 IntroductionAdvisor:
        │                             └─ ia.getClassFilter().matches(beanClass)
        │
        ├─ [3] extendAdvisors(eligibleAdvisors)
        │     └─ AspectJAwareAdvisorAutoProxyCreator.extendAdvisors():
        │           └─ AspectJProxyUtils.makeAdvisorChainAspectJCapableIfNecessary(candidateAdvisors)
        │                 └─ 如果链中没有 ExposeInvocationInterceptor:
        │                       └─ ★ 在链的最前面插入 ExposeInvocationInterceptor.INSTANCE
        │                            作用: 将当前的 MethodInvocation 暴露到 ThreadLocal 中
        │                            使得 @AspectJ advice 方法中可以通过 JoinPoint 获取当前调用信息
        │
        └─ [4] sortAdvisors(eligibleAdvisors)
              └─ AspectJAwareAdvisorAutoProxyCreator.sortAdvisors():
                    └─ AspectJPrecedenceComparator + PartialOrder.sort()
                          ├─ 按 Ordered 接口排序
                          ├─ 同一 @Aspect 内的 advice 按 AspectJ 声明规则排序:
                          │     ├─ After advice: 声明在后的优先级高（后执行）
                          │     └─ 其他 advice: 声明在前的优先级高（先执行）
                          └─ ★ 排序结果是: 高优先级在前 → 意味着最先执行
                              执行顺序: 优先级高 → 优先级低 → 目标方法 → 优先级低后置 → 优先级高后置
```

### 6.4 createProxy() —— 选择代理方式并生成代理

**文件：`AbstractAutoProxyCreator.java:440`**

```
createProxy(beanClass, beanName, specificInterceptors, targetSource)
  └─ buildProxy(beanClass, beanName, specificInterceptors, targetSource, classOnly=false)
        │
        ├─ [1] 创建 ProxyFactory:
        │     ProxyFactory proxyFactory = new ProxyFactory()
        │     proxyFactory.copyFrom(this)  // 复制 copyProxyTargetClass, exposeProxy 等设置
        │
        ├─ [2] ★ 决定代理策略:
        │     if (shouldProxyTargetClass(beanClass, beanName))
        │         → proxyFactory.setProxyTargetClass(true)  // 强制 CGLIB
        │     else
        │         → 检查是否暴露了接口 → 如果有接口则不设置 proxyTargetClass
        │
        ├─ [3] 将 specificInterceptors 适配并添加:
        │     AdvisorAdapterRegistry registry = GlobalAdvisorAdapterRegistry.getInstance()
        │     for each interceptor:
        │         ├─ 如果是 Advisor → proxyFactory.addAdvisor(advisor)
        │         └─ 否则 → registry.wrap(adviceObject)
        │               └─ 如果是 MethodInterceptor → DefaultPointcutAdvisor(Pointcut.TRUE, advice)
        │               └─ 如果是 MethodBeforeAdvice → DefaultPointcutAdvisor(Pointcut.TRUE, advice)
        │               └─ 等等...
        │
        ├─ [4] 设置 targetSource:
        │     proxyFactory.setTargetSource(targetSource)
        │
        └─ [5] ★ 创建代理:
              proxyFactory.getProxy(classLoader)
                ├─ createAopProxy()
                │     └─ DefaultAopProxyFactory.createAopProxy(AdvisedSupport config)
                │           │
                │           ├─ 如果 isOptimize() || isProxyTargetClass() || !hasUserSuppliedInterfaces():
                │           │     ├─ 如果 targetClass 是接口 或 JDK Proxy 类 或 Lambda 类:
                │           │     │     └─ return new JdkDynamicAopProxy(config)
                │           │     └─ 否则:
                │           │           └─ return new ObjenesisCglibAopProxy(config)
                │           │
                │           └─ 否则 (用户指定了接口 && 不强制 proxyTargetClass):
                │                 └─ return new JdkDynamicAopProxy(config)
                │
                └─ aopProxy.getProxy(classLoader)
                      │
                      ├─ 【JDK 动态代理】:
                      │     └─ JdkDynamicAopProxy.getProxy(classLoader)
                      │           ├─ 确定 proxiedInterfaces:
                      │           │     ├─ 合并 advised.getProxiedInterfaces()
                      │           │     ├─ 添加 SpringProxy, Advised, DecoratingProxy 接口
                      │           │     └─ 确保 TargetSource 的 targetClass 的所有接口都在内
                      │           └─ Proxy.newProxyInstance(classLoader, proxiedInterfaces, this)
                      │                 └─ ★ this 即 JdkDynamicAopProxy 自身 (它实现了 InvocationHandler)
                      │                       所有方法调用 → invoke(proxy, method, args)
                      │
                      └─ 【CGLIB 代理】:
                            └─ CglibAopProxy.getProxy(classLoader)
                                  ├─ Enhancer enhancer = new Enhancer()
                                  ├─ enhancer.setSuperclass(proxySuperClass)
                                  ├─ enhancer.setInterfaces(proxiedInterfaces)
                                  ├─ enhancer.setCallbacks([
                                  │     DynamicAdvisedInterceptor,       // AOP_PROXY (0)
                                  │     DynamicAdvisedInterceptor用于TargetSource热替换
                                  │     CglibAopProxy.StaticUnadvisedInterceptor,  // INVOKE_TARGET (1)
                                  │     SerializedNoOp,                  // NO_OVERRIDE (2)
                                  │     StaticDispatcher,                // DISPATCH_TARGET (3)
                                  │     AdvisedDispatcher,               // DISPATCH_ADVISED (4)
                                  │     EqualsInterceptor,               // INVOKE_EQUALS (5)
                                  │     HashCodeInterceptor              // INVOKE_HASHCODE (6)
                                  │   ])
                                  └─ enhancer.create()
                                        └─ ★ 通过 ASM/CGLIB 字节码生成目标类的子类
                                              所有方法 → DynamicAdvisedInterceptor.intercept()
```

---

## 七、AOP 拦截器链的构建与调用

### 7.1 方法调用入口

以 JDK 动态代理为例。当调用 `userService.doSomething()` 时，JVM 将调用路由到：

**文件：`JdkDynamicAopProxy.java:166`**

```
JdkDynamicAopProxy.invoke(proxy, method, args)
  │
  ├─ [1] 特殊方法处理:
  │     ├─ equals → 如果目标类未自定义 equals → return equals(args[0])
  │     ├─ hashCode → 如果目标类未自定义 hashCode → return hashCode()
  │     ├─ DecoratingProxy.getDecoratedClass() → return 目标类
  │     └─ Advised 接口方法 (isFrozen, isProxyTargetClass 等) → 委托给 this.advised
  │
  ├─ [2] 如果 exposeProxy = true:
  │     └─ AopContext.setCurrentProxy(proxy)  // 暴露当前代理到 ThreadLocal
  │
  ├─ [3] ★ 获取目标对象:
  │     target = targetSource.getTarget()
  │     └─ 如果是 SingletonTargetSource: 直接返回持有的单例 bean
  │     └─ 如果是 HotSwappableTargetSource: 返回当前持有的目标
  │     └─ 如果是 CommonsPool2TargetSource: 从对象池 borrow
  │     └─ 如果是 ThreadLocalTargetSource: 从 ThreadLocal 获取
  │
  ├─ [4] ★ 构建拦截器链:
  │     List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass)
  │     └─ 【进入 7.2 节详述】
  │
  ├─ [5] 如果链为空:
  │     └─ AopUtils.invokeJoinpointUsingReflection(target, method, args)
  │           └─ method.invoke(target, args)  // ★ 直接反射调用，无任何增强
  │
  └─ [6] ★ 链不为空:
        MethodInvocation invocation = new ReflectiveMethodInvocation(
                proxy, target, method, args, targetClass, chain)
        retVal = invocation.proceed()
        └─ 【进入 7.3 节详述】
```

### 7.2 拦截器链构建详解

**文件：`AdvisedSupport.java:516` → `DefaultAdvisorChainFactory.java:58`**

```
getInterceptorsAndDynamicInterceptionAdvice(method, targetClass)
  │
  ├─ [1] 检查缓存:
  │     if (this.methodCache != null)
  │         → 从 methodCache 取 (按方法缓存的拦截器链)
  │     else
  │         → 从 cachedInterceptors 取 (共享缓存，当没有方法级切面时)
  │
  └─ [2] 缓存未命中 → 委托给 AdvisorChainFactory:
        └─ DefaultAdvisorChainFactory.getInterceptorsAndDynamicInterceptionAdvice(
                advised, method, targetClass)
              │
              ├─ Advisor[] advisors = config.getAdvisors()  // ★ 获取所有排序好的 Advisor
              ├─ Class<?> actualClass = (targetClass != null ? targetClass : method.getDeclaringClass())
              │
              └─ for each advisor in advisors:
                    │
                    ├─ 【PointcutAdvisor 处理】:
                    │     ├─ [类级别过滤] pointcutAdvisor.getPointcut().getClassFilter().matches(actualClass)
                    │     │     不通过 → 跳过此 advisor
                    │     │
                    │     ├─ [方法级别匹配] MethodMatcher mm = pointcutAdvisor.getPointcut().getMethodMatcher()
                    │     │     mm.matches(method, actualClass)
                    │     │     不通过 → 跳过此 advisor
                    │     │
                    │     └─ 匹配通过:
                    │           ├─ 从 advisor 提取 MethodInterceptor:
                    │           │     MethodInterceptor[] interceptors = registry.getInterceptors(advisor)
                    │           │     └─ DefaultAdvisorAdapterRegistry.getInterceptors(advisor):
                    │           │           ├─ advice = advisor.getAdvice()
                    │           │           ├─ if (advice instanceof MethodInterceptor)
                    │           │           │     → interceptors.add((MethodInterceptor)advice)
                    │           │           │     // 无需适配，直接使用(如 @Around advice / AspectJAroundAdvice)
                    │           │           └─ for each AdvisorAdapter:
                    │           │                 ├─ MethodBeforeAdviceAdapter:
                    │           │                 │     判断 advice 是 MethodBeforeAdvice?
                    │           │                 │     → return MethodBeforeAdviceInterceptor(advice)
                    │           │                 ├─ AfterReturningAdviceAdapter:
                    │           │                 │     判断是 AfterReturningAdvice?
                    │           │                 │     → return AfterReturningAdviceInterceptor(advice)
                    │           │                 └─ ThrowsAdviceAdapter:
                    │           │                       判断是 ThrowsAdvice?
                    │           │                       → return ThrowsAdviceInterceptor(advice)
                    │           │
                    │           └─ 判断 MethodMatcher.isRuntime():
                    │                 ├─ TRUE (动态匹配: 参数参与匹配表达式):
                    │                 │     └─ for each interceptor:
                    │                 │           interceptorList.add(
                    │                 │               new InterceptorAndDynamicMethodMatcher(interceptor, mm))
                    │                 │           // ★ 运行时每次调用都重新检查 MethodMatcher
                    │                 │
                    │                 └─ FALSE (静态匹配: 仅按方法签名匹配):
                    │                       └─ interceptorList.addAll(interceptors)
                    │
                    ├─ 【IntroductionAdvisor 处理】:
                    │     └─ ClassFilter.matches(actualClass) 通过:
                    │           └─ interceptorList.addAll(registry.getInterceptors(advisor))
                    │
                    └─ 【其他 Advisor】:
                          └─ interceptorList.addAll(registry.getInterceptors(advisor))
```

### 7.3 拦截器链调用：ReflectiveMethodInvocation.proceed()

**文件：`ReflectiveMethodInvocation.java:155`**

这是 AOP 责任链模式的**核心**：

```java
public Object proceed() throws Throwable {
    // ★ 终止条件: 所有拦截器都执行完毕
    if (this.currentInterceptorIndex == this.interceptorsAndDynamicMethodMatchers.size() - 1) {
        return invokeJoinpoint();  // ★ 反射调用目标方法
    }

    // ★ 获取下一个拦截器（索引递增）
    Object interceptorOrAdvice =
            this.interceptorsAndDynamicMethodMatchers.get(++this.currentInterceptorIndex);

    if (interceptorOrAdvice instanceof InterceptorAndDynamicMethodMatcher dm) {
        // ★ 动态匹配: 每次调用都重新判断 MethodMatcher
        if (dm.matcher().matches(this.method, targetClass, this.arguments)) {
            return dm.interceptor().invoke(this);  // 匹配 → 执行拦截器
        } else {
            return proceed();  // 不匹配 → 跳过，继续下一个
        }
    } else {
        // ★ 静态匹配: 直接执行拦截器
        return ((MethodInterceptor) interceptorOrAdvice).invoke(this);
    }
}
```

### 7.4 各 Advice 拦截器的 invoke() 实现

每个拦截器的 `invoke(this)` 方法内部都会调用 `mi.proceed()` 来驱动链继续前进：

```
拦截器链 = [
    ExposeInvocationInterceptor,      // index 0
    AspectJAroundAdvice,              // index 1  (@Around)
    MethodBeforeAdviceInterceptor,    // index 2  (@Before)
    AfterReturningAdviceInterceptor,  // index 3  (@AfterReturning)
    AspectJAfterAdvice,               // index 4  (@After)
]

执行流程 (责任链嵌套):

[0] ExposeInvocationInterceptor.invoke(mi)
      ├─ 将 mi 放入 ThreadLocal (供 @AspectJ advice 方法中的 JoinPoint 使用)
      └─ return mi.proceed() ──────────┐
                                        │
[1] AspectJAroundAdvice.invoke(mi)  ←─┘
      ├─ 获取 ProceedingJoinPoint
      ├─ 执行 @Around 方法体中的前置逻辑
      └─ return pjp.proceed() ────────┐  // pjp.proceed() 内部调用 mi.proceed()
                                        │
[2] MethodBeforeAdviceInterceptor.invoke(mi) ←─┘
      ├─ this.advice.before(mi.getMethod(), mi.getArguments(), mi.getThis())
      │     └─ @Before 方法执行
      └─ return mi.proceed() ──────────┐
                                        │
[3] AfterReturningAdviceInterceptor.invoke(mi) ←─┘
      ├─ Object retVal = mi.proceed() ──┐
      │                                  │
[4] AspectJAfterAdvice.invoke(mi) ←─────┘
      ├─ try {
      │     return mi.proceed() ────────────────────────┐
      │   }                                              │
      │   finally {                                      │
      │     // @After 的 finally 保证无论正常/异常都会执行
      │     invokeAdviceMethod(...)  // 执行 @After 方法
      │   }
      │                                                  │
      │  ★ invokeJoinpoint() ←──────────────────────────┘
      │  └─ method.invoke(target, arguments)  // 真正的目标方法
      │
      └─ [3 继续] this.advice.afterReturning(retVal, method, args, target)
            └─ @AfterReturning 方法执行

      └─ [1 继续] @Around 方法体中的后置逻辑

执行时间线:
─────────────────────────────────────────────────────────→
 @Around 前置 → @Before → 目标方法 → @After(finally) → @AfterReturning → @Around 后置
```

### 7.5 各 Advice 拦截器源码要点

```java
// MethodBeforeAdviceInterceptor
public Object invoke(MethodInvocation mi) throws Throwable {
    this.advice.before(mi.getMethod(), mi.getArguments(), mi.getThis());
    return mi.proceed();  // 前置执行后继续链
}

// AfterReturningAdviceInterceptor
public Object invoke(MethodInvocation mi) throws Throwable {
    Object retVal = mi.proceed();  // 先继续链 (到目标方法)
    this.advice.afterReturning(retVal, mi.getMethod(), mi.getArguments(), mi.getThis());
    return retVal;  // 返回目标方法的返回值
}

// ThrowsAdviceInterceptor
public Object invoke(MethodInvocation mi) throws Throwable {
    try {
        return mi.proceed();  // 先继续链
    }
    catch (Throwable ex) {
        // 反射查找匹配异常类型的 afterThrowing 方法
        Method handlerMethod = getExceptionHandler(ex);
        if (handlerMethod != null) {
            invokeHandlerMethod(mi, ex, handlerMethod);
        }
        throw ex;  // 重新抛出异常
    }
}

// AspectJAfterAdvice (实现 MethodInterceptor)
public Object invoke(MethodInvocation mi) throws Throwable {
    try {
        return mi.proceed();  // 先继续链
    }
    finally {
        invokeAdviceMethod(getJoinPointMatch(), null, null);  // ★ finally 保证一定执行
    }
}

// AspectJAroundAdvice (实现 MethodInterceptor)
public Object invoke(MethodInvocation mi) throws Throwable {
    // 创建 MethodInvocationProceedingJoinPoint 包装 mi
    ProxyMethodInvocation pmi = (ProxyMethodInvocation) mi;
    ProceedingJoinPoint pjp = lazyGetProceedingJoinPoint(pmi);
    JoinPointMatch jpm = getJoinPointMatch(pmi);
    // 调用用户的 @Around 方法，传入 pjp
    return invokeAdviceMethod(pjp, jpm, null, null);
    // 用户代码中 pjp.proceed() → mi.proceed() → 继续拦截器链
}

// ExposeInvocationInterceptor (位于链的最前端)
public Object invoke(MethodInvocation mi) throws Throwable {
    MethodInvocation oldInvocation = invocation.get();
    invocation.set(mi);  // ★ ThreadLocal 暴露当前调用
    try {
        return mi.proceed();
    }
    finally {
        invocation.set(oldInvocation);  // 恢复
    }
}
```

---

## 八、循环依赖与三级缓存

### 8.1 三级缓存数据结构

**文件：`DefaultSingletonBeanRegistry.java`**

```
一级缓存: singletonObjects      = ConcurrentHashMap<String, Object>          // 完全初始化好的 bean
二级缓存: earlySingletonObjects  = ConcurrentHashMap<String, Object>          // 提前暴露的早期引用
三级缓存: singletonFactories     = ConcurrentHashMap<String, ObjectFactory<?>> // 生成早期引用的工厂
```

### 8.2 循环依赖解决流程（A ↔ B 场景）

```
场景: A 依赖 B (属性注入), B 依赖 A (属性注入)

时间线:
─────

getBean("a")
  └─ doGetBean("a")
        └─ getSingleton("a", ObjectFactory)  // 单例创建模式
              ├─ beforeSingletonCreation("a")   → singletonsCurrentlyInCreation.add("a")
              └─ createBean("a")
                    └─ doCreateBean("a")
                          ├─ createBeanInstance("a")  → ★ A 实例化完成 (原始对象)
                          ├─ applyMergedBeanDefinitionPostProcessors
                          ├─ ★ addSingletonFactory("a", () -> getEarlyBeanReference("a", mbd, a))
                          │     └─ singletonFactories.put("a", lambda)
                          │     └─ ★★★ A 的早期引用工厂已就位 ★★★
                          │
                          └─ populateBean("a")  // 开始填充 A 的属性
                                └─ @Autowired B b  // 需要注入 B
                                      └─ getBean("b")  // ★ 递归创建 B
                                            └─ doGetBean("b")
                                                  └─ getSingleton("b", ObjectFactory)
                                                        ├─ beforeSingletonCreation("b") → singletonsCurrentlyInCreation.add("b")
                                                        └─ createBean("b")
                                                              └─ doCreateBean("b")
                                                                    ├─ createBeanInstance("b")  → B 实例化完成
                                                                    ├─ addSingletonFactory("b", () -> getEarlyBeanReference("b", mbd, b))
                                                                    │     └─ singletonFactories.put("b", lambda)
                                                                    │
                                                                    └─ populateBean("b")
                                                                          └─ @Autowired A a  // 需要注入 A
                                                                                └─ getBean("a")
                                                                                      └─ doGetBean("a")
                                                                                            └─ ★ getSingleton("a")
                                                                                                  ├─ singletonObjects.get("a") → null
                                                                                                  ├─ isSingletonCurrentlyInCreation("a")? → TRUE!
                                                                                                  ├─ earlySingletonObjects.get("a") → null
                                                                                                  └─ singletonFactories.get("a")
                                                                                                        └─ ★★★ 找到 A 的早期引用工厂 ★★★
                                                                                                        └─ lambda.getObject()
                                                                                                              └─ getEarlyBeanReference("a", mbd, a)
                                                                                                                    └─ 如果 A 需要 AOP → 提前创建代理
                                                                                                                    └─ 返回 A 的早期引用(可能是代理)
                                                                                                        └─ earlySingletonObjects.put("a", earlyRef)
                                                                                                        └─ singletonFactories.remove("a")
                                                                                                        └─ ★ 返回 A 的早期引用给 B
                                                                                └─ ★ B 的 A 字段注入完成
                                                                    └─ initializeBean("b")  → B 初始化完成
                                                        └─ addSingleton("b", b)
                                                              └─ singletonObjects.put("b", b)  // B 进入一级缓存
                                                              └─ singletonFactories.remove("b")
                                                              └─ earlySingletonObjects.remove("b")
                                                        └─ return b
                          └─ ★ A 拿到 B 实例，完成 B 字段注入

                          └─ initializeBean("a")  // A 初始化
                                └─ applyBeanPostProcessorsAfterInitialization
                                      └─ AbstractAutoProxyCreator.postProcessAfterInitialization(a, "a")
                                            ├─ earlyBeanReferences.remove(cacheKey) == a?
                                            │     ★ 这里 a 不等于早期引用(早期引用已在三级缓存中被消费)
                                            │     所以会进入 wrapIfNecessary()
                                            └─ wrapIfNecessary()  → 正常创建 AOP 代理

                          └─ [循环依赖最终校验]
                              earlySingletonReference = getSingleton("a", false)
                              → 从 earlySingletonObjects 中取 (之前 B 获取时放入的)
                              → 如果 exposeObject (after proxy) != bean (原始) 且 != earlyRef:
                                  抛异常! (可能注入的是原始对象，但最终是代理对象)
```

### 8.3 三级缓存的作用总结

| 缓存 | 作用 | 生命周期 |
|------|------|---------|
| `singletonObjects` (一级) | 存储完全初始化好的单例 | `addSingleton()` 时放入，`destroySingleton()` 时移除 |
| `earlySingletonObjects` (二级) | 存储三级缓存工厂生成的早期引用 | 三级缓存的 lambda 被调用后放入，`addSingleton()` 时清除 |
| `singletonFactories` (三级) | 存储能生成早期引用的 ObjectFactory | `addSingletonFactory()` 时放入，lambda 被调用后移除 |

**为什么需要三级缓存而不是两级？**
- 如果只有两级（去掉三级缓存的 lambda），那么早期引用就是原始 bean 对象
- 但有些 bean 需要 AOP 代理，如果 A 在 B 创建时被注入原始对象，而 A 最终被代理，那么 B 中注入的是"脏"的原始对象
- 三级缓存的 lambda 允许在早期引用被获取时，**即时调用 `getEarlyBeanReference()` 来创建代理**，确保 B 中注入的也是代理对象

---

## 九、Spring 7.x 新特性：BeanDefinition 改进

### 9.1 BeanDefinition 增强类型系统

Spring 7.x 引入了更细粒度的 BeanDefinition 类型，便于类型安全和性能优化：

**文件：`org.springframework.beans.factory.support.BeanDefinition`**

```
BeanDefinition
├── AnnotatedBeanDefinition  // 注解驱动的 bean 定义
│   ├── ScannedGenericBeanDefinition    // @ComponentScan 扫描得到的
│   ├── ConfigurationClassBeanDefinition // @Bean 方法生成的
│   └── AnnotatedGenericBeanDefinition   // 手动注册的注解 bean
├── AbstractBeanDefinition          // 抽象基类
│   ├── RootBeanDefinition           // 合并后的完整定义
│   └── GenericBeanDefinition       // 原始注册的定义
├── ListableBeanDefinition         // 可列表的 bean 定义
│   └── MutableBeanDefinition        // 可变的 bean 定义
└── AliasAwareBeanDefinition      // 支持别名的 bean 定义
    └── AbstractBeanDefinition (immediate)
```

**关键改进：**

```java
// 1. 支持泛型参数化类型
GenericBeanDefinition genericBd = new GenericBeanDefinition();
genericBd.setBeanClass(MyRepository.class);
genericBd.setAttribute("resolvableType", ResolvableType.forClass(MyRepository.class));

// 2. 元数据增强（用于 SpringDoc、Kotlin 等）
genericBd.setAttribute("className", "com.example.MyBean");
genericBd.setAttribute("description", "User service implementation");
genericBd.setAttribute("primary", true);

// 3. BeanDefinition 属性注册表（避免硬编码）
Map<String, Object> attributes = new HashMap<>();
attributes.put("securityLevel", "internal");
attributes.put("apiVersion", "v1");
genericBd.setAttributes(attributes);
```

### 9.2 BeanDefinition 继承优化

Spring 7.x 改进了 BeanDefinition 的继承合并算法：

**文件：`org.springframework.beans.factory.support.AbstractBeanFactory:975`**

```java
protected RootBeanDefinition getMergedBeanDefinition(String beanName, RootBeanDefinition mbd, Object bean) {
    synchronized (mbd.postProcessingLock) {
        Object cachedMergedBeanDefinition = this.mergedBeanDefinitions.get(beanName);
        if (cachedMergedBeanDefinition != null && !mbd.isStale()) {
            return (RootBeanDefinition) cachedMergedBeanDefinition;
        }

        // 1. 如果有父定义，递归合并
        if (mbd.getParentName() != null) {
            RootBeanDefinition parentBd = getMergedBeanDefinition(mbd.getParentName(), null, null);
            RootBeanDefinition merged = new RootBeanDefinition(parentBd);
            merged.setParentName(null);
            merged.copyFromFrom(mbd);
            // ... 后续合并逻辑
        } else {
            // 2. 新建合并定义
            RootBeanDefinition merged = createBeanDefinition(mbd.getBeanClass(), mbd.getParentName());
            merged.copyFromFrom(mbd);
        }

        // 3. 合并注解元数据
        if (mbd.hasBeanClass()) {
            Class<?> beanClass = mbd.getBeanClass();
            // 合并 @ComponentScan、@Bean 等注解的属性
        }

        this.mergedBeanDefinitions.put(beanName, merged);
        return merged;
    }
}
```

**合并策略：**

1. **类继承**：子类的 bean 定义覆盖父类的属性
2. **属性覆盖**：XML 中的属性覆盖注解配置
3. **元数据合并**：合并所有注解的元数据

### 9.3 BeanDefinition 注册性能优化

Spring 7.x 使用了更高效的并发注册机制：

**文件：`DefaultListableBeanFactory.java:993`**

```java
public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition) throws BeanDefinitionStoreException {
    Assert.hasText(beanName, "Bean name must not be empty");
    Assert.notNull(beanDefinition, "BeanDefinition must not be null");

    // 1. 校验 BeanDefinition
    String[] oldBeanNames = (this.beanDefinitionNames.contains(beanName) ? new String[]{beanName} : null);
    String[] removedBeanNames = checkNameUniqueness(beanName, beanDefinition);

    // 2. 乐观锁：先快速检查
    if (!this.beanDefinitionNames.contains(beanName) && this.singletonObjects.containsKey(beanName)) {
        throw new BeanDefinitionStoreException("Cannot re-register existing definition for bean '" + beanName + "'");
    }

    // 3. 并发注册：使用 ConcurrentHashMap
    BeanDefinition oldDefinition = this.beanDefinitionMap.get(beanName);
    this.beanDefinitionMap.put(beanName, beanDefinition);
    this.beanDefinitionNames.add(beanName);

    // 4. 清除合并缓存
    if (oldDefinition != null) {
        this.beanDefinitionNames.remove(beanName);
        this.mergedBeanDefinitions.remove(beanName);
    }

    // 5. 类型缓存清除
    clearByTypeCache();
}
```

### 9.4 @Lazy 的增强支持

Spring 7.x 改进了 `@Lazy` 的实现和性能：

```java
// 1. 延迟解析 BeanDefinition
@Lazy
public class LazyBean {
    @Autowired
    private ExpensiveService expensiveService;  // 延迟注入
}

// 2. 延迟解析方法
@Bean
@Lazy
public SomeBean lazyBean() {
    return new SomeBean();
}

// 3. 延迟初始化作用域
@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS, value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class PrototypeBean {
    // 每个 bean 实例都是延迟初始化的
}
```

**实现原理：**

```java
// AbstractBeanFactory.java
protected Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
    // 检查一级缓存
    Object singletonObject = this.singletonObjects.get(beanName);
    if (singletonObject == null) {
        if (this.singletonsCurrentlyInCreation.contains(beanName)) {
            // 双重检查锁 + 原子操作
            ObjectFactory<?> earlySingletonReference = this.earlySingletonObjects.get(beanName);
            if (earlySingletonReference != null) {
                return earlySingletonReference.getObject();
            }
        }

        synchronized (this.singletonObjects) {
            // 再次检查（防止多线程创建重复）
            singletonObject = this.singletonObjects.get(beanName);
            if (singletonObject == null) {
                // 添加到正在创建集合
                beforeSingletonCreation(beanName);
                try {
                    // 执行创建逻辑
                    singletonObject = singletonFactory.getObject();
                    // ... 后续处理
                } finally {
                    afterSingletonCreation(beanName);
                }
            }
        }
    }
    return singletonObject;
}
```

---

## 十、Spring 7.x 新特性：AOT 与 GraalVM Native Image

### 10.1 AOT（Ahead-of-Time）处理流程

Spring 7.x 在构建时进行 AOT 处理，优化 GraalVM Native Image 的启动性能：

**文件：`org.springframework.aot.generate.GenerationContext`**

```
Native Image 构建流程:

1. Spring Boot 应用启动
   └─ SpringBootAotProcessor.process()
       ├─ 检测 @Configuration 类
       ├─ 处理 @Bean 方法
       ├─ 处理 @Conditional 注解
       ├─ 生成原生镜像 hint 文件
       └─ 生成优化后的配置

2. Maven/Gradle Native Image 插件
   └─ 运行 Spring AOT 处理器
       └─ 编译时生成 native-image.properties

3. GraalVM Native Image 编译
   └─ 使用 hint 文件
       ├─ 配置反射访问
       ├─ 配置资源加载
       ├─ 配置动态代理
       └─ 优化启动速度和内存占用
```

### 10.2 RuntimeHints 系统

Spring 7.x 提供了丰富的 RuntimeHints API 来引导 GraalVM Native Image：

**文件：`org.springframework.aot.hint.RuntimeHints`**

```java
// 1. 反射访问配置
@RegisterReflectionForBinding(User.class)
public class UserRepository {
    private Long id;
    private String name;
}

// 2. 代码中动态配置 RuntimeHints
@Component
public class MyService {
    private final RuntimeHints runtimeHints;

    public MyService(RuntimeHints runtimeHints) {
        this.runtimeHints = runtimeHints;

        // 配置反射访问
        this.runtimeHints.reflection()
            .registerType(MyEntity.class,
                MemberCategory.ALL_FIELDS);

        // 配置资源加载
        this.runtimeHints.resource()
            .registerPattern("config/*.properties");

        // 配置动态代理
        this.runtimeHints.proxy()
            .registerInterface(Runnable.class);
    }
}
```

**RuntimeHints 的四大类：**

1. **ReflectionHints**：反射访问配置
   - 字段访问（PUBLIC_FIELDS）
   - 方法调用（PUBLIC_METHODS）
   - 构造器（CONSTRUCTORS）
   - 参数化类型（PARAMETIZED_TYPES）

2. **ResourceHints**：资源加载配置
   - Classpath 资源（CLASSPATH_RESOURCES）
   - File 资源（FILE_RESOURCES）
   - 文本内容

3. **ProxyHints**：动态代理配置
   - JDK 动态代理接口
   - CGLIB 子类代理

4. **SerializationHints**：序列化配置
   - 需要序列化的类
   - 需要特殊处理的字段

### 10.3 BeanDefinition AOT 处理

Spring 7.x 在构建时生成优化的 BeanDefinition 注册代码：

**文件：`org.springframework.beans.factory.aot.BeanDefinitionRegistrationAotContribution`**

```java
// 1. AOT 处理器在构建时扫描 @Configuration 类
public class ConfigurationClassAotProcessor {
    public List<BeanRegistrationAotContribution> process(Set<ConfigurationClass> configClasses) {
        List<BeanRegistrationAotContribution> contributions = new ArrayList<>();

        for (ConfigurationClass configClass : configClasses) {
            // 生成 bean 注册代码
            contributions.add(new ConfigurationClassBeanRegistrationAotContribution(configClass));
        }

        return contributions;
    }
}

// 2. 生成的优化代码
public class BeanRegistrationAotContribution {
    public void applyTo(AotWiringContext context) {
        // 1. 预计算 bean 依赖关系
        Set<String> dependencies = calculateDependencies();

        // 2. 生成可执行注册代码
        context.registerBean("myService", MyService.class,
            (codeGenerator, context) -> {
                // 使用静态工厂方法
                codeGenerator.append("return MyServiceFactory.create();");
            });
    }
}
```

### 10.4 Native Image 中的 Spring 性能优化

**启动性能优化：**

1. **延迟初始化**：`@Lazy` 和 `@Profile` 在构建时被优化
2. **类加载优化**：AOT 提前加载必需的类
3. **反射缓存**：反射操作被缓存和优化
4. **代理优化**：动态代理被静态代理替代

**内存占用优化：**

1. **字符串去重**：相同字符串共享实例
2. **数值优化**：小范围数值使用压缩表示
3. **资源裁剪**：只包含运行时必需的资源

**示例配置：**

```properties
# native-image.properties
Args = \
    -H:EnableReflectionTracking=true \
    -H:ReflectionConfigurationFiles=reflection-config.json \
    -H:ResourceConfigurationFiles=resource-config.json \
    -H:ProxyConfigurationFiles=proxy-config.json \
    -H:SerializationConfigurationFiles=serialization-config.json \
    -H:MaximumStackSize=1m
```

---

## 十一、并发安全与性能优化

### 11.1 ConcurrentHashMap 在 BeanFactory 中的应用

Spring 使用多种 ConcurrentHashMap 模式保证线程安全：

**文件：`DefaultListableBeanFactory.java`**

```java
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
        implements ConfigurableListableBeanFactory, BeanDefinitionRegistry {

    // 一级缓存：完全初始化的 bean
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

    // 二级缓存：早期引用（处理循环依赖）
    private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

    // 三级缓存：早期引用工厂
    private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

    // 单例 bean 注册表
    private final Set<String> registeredSingletons = new LinkedHashSet<>(256);

    // 正在创建的 bean（线程安全）
    private final Set<String> singletonsCurrentlyInCreation =
            Collections.newSetFromMap(new ConcurrentHashMap<>(16));

    // BeanDefinition 缓存
    private final Map<String, BeanDefinition> beanDefinitionMap =
            new ConcurrentHashMap<>(256);

    // BeanDefinition 名称列表
    private volatile List<String> beanDefinitionNames = new ArrayList<>(256);

    // 正在创建的 bean（ThreadLocal，用于原型作用域）
    private final ThreadLocal<Set<String>> prototypesCurrentlyInCreation =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new ConcurrentHashMap<>(16)));
}
```

### 11.2 锁优化策略

**分级锁机制：**

```java
// 1. 细粒度锁：按 beanName 锁定
protected Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
    Object singletonObject = this.singletonObjects.get(beanName);
    if (singletonObject == null) {
        if (this.singletonsCurrentlyInCreation.contains(beanName)) {
            ObjectFactory<?> earlySingletonReference = this.earlySingletonObjects.get(beanName);
            if (earlySingletonReference != null) {
                return earlySingletonReference.getObject();
            }
        }

        // 使用双重检查锁 + 原子操作
        synchronized (this.singletonObjects) {
            singletonObject = this.singletonObjects.get(beanName);
            if (singletonObject == null) {
                beforeSingletonCreation(beanName);

                try {
                    // 创建 bean
                    singletonObject = singletonFactory.getObject();

                    // 添加到缓存
                    addSingleton(beanName, singletonObject);
                } finally {
                    afterSingletonCreation(beanName);
                }
            }
        }
    }
    return singletonObject;
}
```

**锁优化要点：**

1. **双重检查锁**：第一次检查不加锁，减少竞争
2. **细粒度锁**：按 beanName 分组，避免全局锁
3. **原子操作**：使用 `ConcurrentHashMap.putIfAbsent` 等原子方法

### 11.3 性能优化技巧

**1. BeanDefinition 缓存**

```java
// 缓存合并后的 BeanDefinition
private final Map<String, RootBeanDefinition> mergedBeanDefinitions = new ConcurrentHashMap<>(256);

protected RootBeanDefinition getMergedLocalBeanDefinition(String beanName) throws BeansException {
    RootBeanDefinition mbd = this.mergedBeanDefinitions.get(beanName);
    if (mbd != null && !mbd.isStale()) {
        return mbd;
    }

    synchronized (mbd.postProcessingLock) {
        if (mbd == null || mbd.isStale()) {
            // 合并逻辑
            mbd = createMergedBeanDefinition(beanName, mbd, null);
            this.mergedBeanDefinitions.put(beanName, mbd);
        }
        return mbd;
    }
}
```

**2. 方法级拦截器链缓存**

```java
// 缓存方法对应的拦截器链
private final Map<Method, List<Object>> methodCache = new ConcurrentHashMap<>(256);

public List<Object> getInterceptorsAndDynamicInterceptionAdvice(
        Method method, Class<?> targetClass) {

    Object cacheKey = getCacheKey(method, targetClass);
    List<Object> cached = this.methodCache.get(cacheKey);

    if (cached == null) {
        synchronized (this.methodCache) {
            cached = this.methodCache.get(cacheKey);
            if (cached == null) {
                // 构建拦截器链
                cached = buildInterceptorChain(method, targetClass);
                this.methodCache.put(cacheKey, cached);
            }
        }
    }
    return cached;
}
```

**3. 延迟初始化优化**

```java
// 只初始化非懒加载的 bean
public void preInstantiateSingletons() throws BeansException {
    List<String> beanNames = new ArrayList<>(this.beanDefinitionNames);
    for (String beanName : beanNames) {
        RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
        if (!mbd.isAbstract() && mbd.isSingleton() && !mbd.isLazyInit()) {
            getBean(beanName);
        }
    }
}
```

### 11.4 原型作用域的并发处理

**ThreadLocal 保证原型 bean 的隔离：**

```java
protected Object getSingleton(String beanName, boolean allowEarlyReference) {
    // 一级缓存检查
    Object singletonObject = this.singletonObjects.get(beanName);
    if (singletonObject != null) {
        return singletonObject;
    }

    // 二级缓存检查（循环依赖）
    Object singletonObject = this.earlySingletonObjects.get(beanName);
    if (singletonObject != null) {
        return singletonObject;
    }

    // 三级缓存检查（提前暴露）
    ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
    if (singletonFactory != null) {
        Object singletonObject = singletonFactory.getObject();
        this.earlySingletonObjects.put(beanName, singletonObject);
        this.singletonFactories.remove(beanName);
        return singletonObject;
    }

    return null;
}

// 原型作用域处理
protected Object createBean(String beanName, RootBeanDefinition mbd, Object[] args) {
    // ... 实例化逻辑

    Object bean;
    if (mbd.isPrototype()) {
        // 原型模式：每次都创建新实例
        beforePrototypeCreation(beanName);
        try {
            bean = doCreateBean(beanName, mbd, args);
        } finally {
            afterPrototypeCreation(beanName);
        }
    } else {
        // 单例模式
        bean = getSingleton(beanName, () -> doCreateBean(beanName, mbd, args));
    }

    return bean;
}
```

---

## 十二、异常处理与恢复机制

### 12.1 Bean 创建异常处理

Spring 提供了多层异常处理机制：

**文件：`AbstractAutowireCapableBeanFactory.java:718`**

```java
protected Object doCreateBean(String beanName, RootBeanDefinition mbd, Object[] args) {
    BeanWrapper instanceWrapper = null;
    Object bean;

    try {
        // 阶段1：实例化
        instanceWrapper = createBeanInstance(beanName, mbd, args);
        bean = instanceWrapper.getWrappedInstance();

        // 阶段2：合并 BD 后处理
        applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);

        // 阶段3：提前暴露（三级缓存）
        boolean earlySingletonExposure = (mbd.isSingleton() &&
                this.allowCircularReferences &&
                isSingletonCurrentlyInCreation(beanName));

        if (earlySingletonExposure) {
            addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
        }

        // 阶段4：属性填充
        Object exposedObject = bean;
        try {
            populateBean(beanName, mbd, instanceWrapper);
        } catch (Throwable ex) {
            throw new BeanCreationException(beanName, "Instantiation of bean failed", ex);
        }

        // 阶段5：初始化
        exposedObject = initializeBean(beanName, exposedObject, mbd);

        // 阶段6：循环依赖校验
        if (earlySingletonExposure) {
            Object earlySingletonReference = getSingleton(beanName, false);
            if (earlySingletonReference != null) {
                if (exposedObject == bean) {
                    exposedObject = earlySingletonReference;
                } else if (!this.allowRawInjectionDespiteWrapping) {
                    // 抛异常：代理不一致
                    throw new BeanCurrentlyInCreationException(beanName,
                        "Bean is being eagerly created during initialization despite being already " +
                        "part of the creation chain: " + beanName);
                }
            }
        }

        // 阶段7：注册销毁方法
        registerDisposableBeanIfNecessary(beanName, bean, mbd);

        return exposedObject;

    } catch (BeanCreationException ex) {
        throw ex;
    } catch (Throwable ex) {
        throw new BeanCreationException(beanName, "Unexpected exception during bean creation", ex);
    }
}
```

### 12.2 依赖注入异常处理

**按类型自动装配的候选选择：**

```java
public List<String> determineCandidateNames(String beanName, Class<?> requiredType) {
    Set<String> candidates = new LinkedHashSet<>();
    for (String beanName : this.beanDefinitionNames) {
        if (isEligibleForBeanDefinition(beanName, requiredType)) {
            candidates.add(beanName);
        }
    }

    // 如果没有候选，抛出异常
    if (candidates.isEmpty()) {
        String message = String.format(
            "Could not resolve autowire candidates for bean '%s': expected at least 1 bean which provides " +
            "type '%s'. There are %d entries in total.",
            beanName, requiredType.getName(), this.beanDefinitionNames.size());
        throw new NoSuchBeanDefinitionException(beanName, message);
    }

    return new ArrayList<>(candidates);
}
```

**按名称自动装配的异常处理：**

```java
public Object resolveDependency(DependencyDescriptor descriptor, String requestingBeanName) {
    try {
        // 1. 查找匹配的 bean
        String autowiredBeanName = descriptor.getDependencyName();
        Class<?> autowiredBeanType = descriptor.getRequiredType();

        // 2. 按名称查找
        if (autowiredBeanName != null) {
            if (this.containsBean(autowiredBeanName)) {
                return this.getBean(autowiredBeanName);
            }
        }

        // 3. 按类型查找
        Map<String, Object> matchingBeans = findAutowireCandidates(beanName, autowiredBeanType, descriptor);

        // 4. 处理多候选
        if (matchingBeans.isEmpty()) {
            throw new NoSuchBeanDefinitionException(beanName,
                String.format("Expected to find %d bean(s) of type '%s' for autowire parameter: %s",
                    1, autowiredBeanType, descriptor));
        }

        // 5. 选择最优候选
        return resolveCandidate(autowiredBeanName, autowiredBeanType, matchingBeans);

    } catch (BeansException ex) {
        throw new UnsatisfiedDependencyException(beanName, descriptor.getResolvableType(), descriptor, ex);
    }
}
```

### 12.3 BeanPostProcessor 异常处理

**BPP 的异常传播：**

```java
public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
        throws BeansException {

    Object result = existingBean;
    for (BeanPostProcessor processor : getBeanPostProcessors()) {
        try {
            Object current = processor.postProcessBeforeInitialization(result, beanName);
            if (current == null) {
                return result;
            }
            result = current;
        } catch (BeanCreationException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new BeanCreationException(beanName, "BeanPostProcessor failed during initialization", ex);
        }
    }
    return result;
}
```

### 12.4 循环依赖的异常处理

**循环依赖检测与报告：**

```java
protected boolean isPrototypeCurrentlyInCreation(String beanName) {
    Set<String> prototypesCurrentlyInCreation = this.prototypesCurrentlyInCreation.get();

    if (prototypesCurrentlyInCreation == null) {
        return false;
    }

    return prototypesCurrentlyInCreation.contains(beanName);
}

protected Object doCreateBean(String beanName, RootBeanDefinition mbd, Object[] args) {
    // ...

    // 检查原型循环依赖
    if (mbd.isPrototype()) {
        if (isPrototypeCurrentlyInCreation(beanName)) {
            throw new BeanCurrentlyInCreationException(beanName,
                "Illegal recursive bean creation: '" + beanName + "'");
        }
    }

    // ...

    // 检查单例循环依赖
    if (mbd.isSingleton()) {
        if (isSingletonCurrentlyInCreation(beanName)) {
            throw new BeanCurrentlyInCreationException(beanName,
                "Bean '" + beanName + "' is currently being created: " +
                "Is there an unresolvable circular reference?");
        }
    }

    // ...
}
```

### 12.5 Bean 初始化失败的处理

**后处理阶段失败的处理：**

```java
protected Object initializeBean(String beanName, Object bean, RootBeanDefinition mbd) {
    try {
        // 5.1 Aware 回调
        invokeAwareMethods(beanName, bean);

        // 5.2 前置处理
        Object wrappedBean = applyBeanPostProcessorsBeforeInitialization(bean, beanName);

        // 5.3 初始化方法
        invokeInitMethods(beanName, wrappedBean, mbd);

        // 5.4 后置处理
        wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);

        return wrappedBean;

    } catch (Throwable ex) {
        throw new BeanCreationException(beanName,
            "Initialization of bean failed", ex);
    }
}

// 初始化方法执行失败的处理
private void invokeInitMethods(String beanName, Object bean, RootBeanDefinition mbd)
        throws Throwable {

    // 执行 InitializingBean.afterPropertiesSet()
    if (mbd.getInitMethodName() != null) {
        invokeCustomInitMethod(beanName, bean, mbd.getInitMethodName(), mbd.getInitArgs());

    } else if (bean instanceof InitializingBean) {
        try {
            ((InitializingBean) bean).afterPropertiesSet();
        } catch (Throwable ex) {
            throw new BeanCreationException(beanName,
                "Invocation of afterPropertiesSet method failed", ex);
        }
    }
}
```

### 12.6 自定义错误处理

**@ExceptionHandler 和全局异常处理：**

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Bean 创建异常
    @ExceptionHandler(BeanCreationException.class)
    public ResponseEntity<ErrorResponse> handleBeanCreationException(BeanCreationException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("BEAN_CREATION_FAILED", ex.getMessage()));
    }

    // 依赖注入异常
    @ExceptionHandler(NoSuchBeanDefinitionException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchBeanDefinitionException(
            NoSuchBeanDefinitionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("DEPENDENCY_MISSING", ex.getMessage()));
    }

    // 循环依赖异常
    @ExceptionHandler(BeanCurrentlyInCreationException.class)
    public ResponseEntity<ErrorResponse> handleCircularReferenceException(
            BeanCurrentlyInCreationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse("CIRCULAR_DEPENDENCY", ex.getMessage()));
    }
}
```

**错误恢复策略：**

1. **Bean 自动恢复**：某些情况下自动重试
2. **降级处理**：使用备用 bean
3. **回滚机制**：事务回滚
4. **重定向**：提示用户重新创建

---

## 附录：关键源码文件索引

### IoC 容器
| 类名 | 文件路径 (相对于 `spring-beans/.../support/`) |
|------|------|
| `DefaultSingletonBeanRegistry` | `DefaultSingletonBeanRegistry.java` |
| `DefaultListableBeanFactory` | `DefaultListableBeanFactory.java` |
| `AbstractBeanFactory` | `AbstractBeanFactory.java` |
| `AbstractAutowireCapableBeanFactory` | `AbstractAutowireCapableBeanFactory.java` |
| `AbstractApplicationContext` | `spring-context/.../support/AbstractApplicationContext.java` |
| `PostProcessorRegistrationDelegate` | `spring-context/.../support/PostProcessorRegistrationDelegate.java` |

### AOP
| 类名 | 文件路径 (相对于 `spring-aop/.../aop/`) |
|------|------|
| `AbstractAutoProxyCreator` | `framework/autoproxy/AbstractAutoProxyCreator.java` |
| `AbstractAdvisorAutoProxyCreator` | `framework/autoproxy/AbstractAdvisorAutoProxyCreator.java` |
| `DefaultAdvisorAutoProxyCreator` | `framework/autoproxy/DefaultAdvisorAutoProxyCreator.java` |
| `AnnotationAwareAspectJAutoProxyCreator` | `aspectj/annotation/AnnotationAwareAspectJAutoProxyCreator.java` |
| `AspectJAwareAdvisorAutoProxyCreator` | `aspectj/autoproxy/AspectJAwareAdvisorAutoProxyCreator.java` |
| `JdkDynamicAopProxy` | `framework/JdkDynamicAopProxy.java` |
| `CglibAopProxy` | `framework/CglibAopProxy.java` |
| `ReflectiveMethodInvocation` | `framework/ReflectiveMethodInvocation.java` |
| `DefaultAdvisorChainFactory` | `framework/DefaultAdvisorChainFactory.java` |
| `DefaultAopProxyFactory` | `framework/DefaultAopProxyFactory.java` |
| `AdvisedSupport` | `framework/AdvisedSupport.java` |
| `DefaultAdvisorAdapterRegistry` | `framework/adapter/DefaultAdvisorAdapterRegistry.java` |

---

> 📅 文档生成日期：2026-06-10  
> 📦 源码版本：Spring Framework 7.x  
> 🔍 基于源码路径：`D:\Spring-Framework\`
