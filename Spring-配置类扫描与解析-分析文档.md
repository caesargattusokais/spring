<!--
 Copyright 2026, Spring Framework Source Analysis
 基于 Spring Framework 7.x 源码
 配置类扫描与解析机制详细分析
-->

# Spring 配置类扫描与解析机制详细分析

> 基于 Spring Framework 7.x 源码，逐方法追踪完整调用链

---

## 目录

1. [概述：配置类处理的整体流程](#一概述配置类处理的整体流程)
2. [配置类候选检测：ConfigurationClassUtils](#二配置类候选检测configurationclassutils)
3. [配置类后处理器：ConfigurationClassPostProcessor](#三配置类后处理器configurationclasspostprocessor)
4. [配置类解析器：ConfigurationClassParser](#四配置类解析器configurationclassparser)
5. [@ComponentScan 扫描机制](#五componentscan-扫描机制)
6. [@Import 导入机制](#六import-导入机制)
7. [@Bean 方法注册：ConfigurationClassBeanDefinitionReader](#七bean-方法注册configurationclassbeandefinitionreader)
8. [@Conditional 条件评估：ConditionEvaluator](#八conditional-条件评估conditionevaluator)
9. [配置类 CGLIB 增强：ConfigurationClassEnhancer](#九配置类-cglib-增强configurationclassenhancer)
10. [完整调用链总结](#十完整调用链总结)
11. [关键源文件索引](#附录关键源文件索引)

---

## 一、概述：配置类处理的整体流程

Spring 对 `@Configuration` / `@Component` 等注解配置类的处理分布在容器启动的不同阶段。整体分为**三个大阶段**：

### 1.1 阶段一：初始 BeanDefinition 注册（refresh 之前）

用户创建 `AnnotationConfigApplicationContext` 时，构造函数中完成两件事：

- `AnnotatedBeanDefinitionReader` 将主配置类（如 `AppConfig.class`）注册为 BeanDefinition
- `AnnotatedBeanDefinitionReader` 同时注册一批基础设施 BeanDefinition（如 `ConfigurationClassPostProcessor`、`AutowiredAnnotationBeanPostProcessor` 等）

详见 [Spring-IoC-AOP-分析文档.md 1.3 节](Spring-IoC-AOP-分析文档.md)。

### 1.2 阶段二：配置类解析（refresh() → invokeBeanFactoryPostProcessors）

这是本文重点分析的部分。在 `refresh()` 的第 5 步 `invokeBeanFactoryPostProcessors()` 中：

1. `ConfigurationClassPostProcessor` 作为 `BeanDefinitionRegistryPostProcessor` 被调用
2. 它的 `postProcessBeanDefinitionRegistry()` 方法触发 `processConfigBeanDefinitions()`
3. 创建 `ConfigurationClassParser` 解析所有配置类候选
4. 创建 `ConfigurationClassBeanDefinitionReader` 将解析结果注册为 BeanDefinition

### 1.3 阶段三：配置类增强（postProcessBeanFactory）

在 `postProcessBeanFactory()` 中，`ConfigurationClassPostProcessor` 对 FULL 模式的 `@Configuration` 类进行 CGLIB 增强，确保 `@Bean` 方法间的调用仍然走容器（保证单例语义）。

### 1.4 核心组件协作图

```
ConfigurationClassPostProcessor.processConfigBeanDefinitions()
    |-- ConfigurationClassUtils.checkConfigurationClassCandidate()  (检测候选)
    |-- ConfigurationClassParser.parse()                             (解析)
    |       |-- processConfigurationClass()      (包装：条件+去重)
    |       |-- doProcessConfigurationClass()    (核心：逐注解处理)
    |       |       |-- processMemberClasses()   (@Component: 递归处理成员类)
    |       |       |-- @PropertySource          (加载属性源)
    |       |       |-- @ComponentScan           (→ ComponentScanAnnotationParser → ClassPathBDScanner)
    |       |       |-- processImports()         (@Import: 直接导入/ImportSelector/DeferredImportSelector/BDRegistrar)
    |       |       |-- @ImportResource          (导入 XML 资源)
    |       |       |-- @Bean 方法搜集           (收集到 ConfigurationClass.beanMethods)
    |       |       |-- 接口默认方法              (processInterfaces)
    |       |       |-- 父类递归                 (sourceClass.getSuperClass())
    |       |-- deferredImportSelectorHandler.process()  (最后处理延迟导入)
    |-- ConfigurationClassBeanDefinitionReader.loadBeanDefinitions()  (注册)
            |-- registerBeanDefinitionForImportedConfigurationClass()
            |-- loadBeanDefinitionsForBeanMethod()
            |-- loadBeanDefinitionsFromImportedResources()
            |-- loadBeanDefinitionsFromImportBeanDefinitionRegistrars()
```

---

## 二、配置类候选检测：ConfigurationClassUtils

**源文件**: `spring-context/.../annotation/ConfigurationClassUtils.java`

在 `ConfigurationClassPostProcessor.processConfigBeanDefinitions()` 的第一个阶段，遍历容器中所有已注册的 BeanDefinition，调用 `checkConfigurationClassCandidate()` 判断是否为配置类候选。

### 2.1 `checkConfigurationClassCandidate()` 完整流程

```
checkConfigurationClassCandidate(BeanDefinition beanDef, MetadataReaderFactory factory)
│
├─ [Step 1] 预检查
│   ├─ className == null → return false
│   └─ factoryMethodName != null → return false (不通过工厂方法创建的 bean)
│
├─ [Step 2] 获取 AnnotationMetadata (三种途径)
│   ├─ AnnotatedBeanDefinition → 直接复用预解析的 metadata
│   ├─ AbstractBeanDefinition.hasBeanClass() → AnnotationMetadata.introspect(beanClass)
│   └─ 其他 → factory.getMetadataReader(className) → ASM 读取
│        └─ IOException → return false
│
├─ [Step 3] 排除基础设施类
│   ├─ BeanFactoryPostProcessor 实现类 → return false
│   ├─ BeanPostProcessor 实现类 → return false
│   ├─ AopInfrastructureBean 实现类 → return false
│   └─ EventListenerFactory 实现类 → return false
│
├─ [Step 4] 判定 FULL vs LITE
│   ├─ 有 @Configuration 且 proxyBeanMethods != false → FULL
│   │   └─ beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, "full")
│   │
│   ├─ 有 @Configuration 但 proxyBeanMethods = false → LITE
│   ├─ CANDIDATE_ATTRIBUTE = true → LITE
│   ├─ isConfigurationCandidate(metadata) = true → LITE
│   │   └─ 检查是否有 @Component/@ComponentScan/@Import/@ImportResource
│   │   └─ 或 检查是否有 @Bean 方法
│   │
│   └─ 都不满足 → return false
│
└─ [Step 5] 记录 @Order 值
    └─ Integer order = getOrder(metadata)
    └─ beanDef.setAttribute(ORDER_ATTRIBUTE, order)
```

### 2.2 FULL vs LITE 模式对比

| 特性 | FULL 模式 | LITE 模式 |
|------|----------|-----------|
| 触发条件 | `@Configuration(proxyBeanMethods=true)` (默认) | `@Component`/`@ComponentScan`/`@Import`/`@ImportResource`/`@Bean` 方法 |
| CGLIB 增强 | **是**，`postProcessBeanFactory` 中增强 | **否** |
| @Bean 方法间调用 | 走容器（保证单例） | 直接调用（可能创建多例） |
| 性能 | 有 CGLIB 开销 | 无代理开销 |
| 适用场景 | 需要 @Bean 方法间引用的场景 | 简单组件、不再引用 @Bean 方法的场景 |

### 2.3 `isConfigurationCandidate()` 判定逻辑

```java
static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
    // 排除接口和注解
    if (metadata.isInterface()) return false;

    // 检查是否有核心配置注解
    for (String indicator : candidateIndicators) {
        if (metadata.isAnnotated(indicator)) return true;
        // candidateIndicators = {Component, ComponentScan, Import, ImportResource}
    }

    // 最后检查是否有 @Bean 方法
    return hasBeanMethods(metadata);
}
```

这表明**任何含有 `@Bean` 方法的类**都会被当作 Lite 配置类处理，即使它没有任何 Spring 注解。

---

## 三、配置类后处理器：ConfigurationClassPostProcessor

**源文件**: `spring-context/.../annotation/ConfigurationClassPostProcessor.java`

### 3.1 类定义与优先级

```java
public class ConfigurationClassPostProcessor implements
        BeanDefinitionRegistryPostProcessor,  // ← 核心：可操作 BeanDefinition 注册表
        PriorityOrdered,                       // 最高优先级
        ResourceLoaderAware, EnvironmentAware, BeanClassLoaderAware {

    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    // LOWEST_PRECEDENCE = Integer.MAX_VALUE
    // 但在 PriorityOrdered 中仍高于 Ordered 的所有实现
}
```

**关键点**：`ConfigurationClassPostProcessor` 实现了 `PriorityOrdered`，优先级在所有 `BeanFactoryPostProcessor` 中最高，确保在用户自定义的 BFPP 之前执行。

### 3.2 `processConfigBeanDefinitions()` 详细流程

**这是整个配置类处理的入口方法**，在 `invokeBeanFactoryPostProcessors()` 的**第一轮**中被调用。

```
processConfigBeanDefinitions(BeanDefinitionRegistry registry)
│
├─ [Step 1] 收集所有配置类候选
│   for each beanName in registry.getBeanDefinitionNames():
│       beanDef = registry.getBeanDefinition(beanName)
│       if beanDef 已处理过 (有 CONFIGURATION_CLASS_ATTRIBUTE):
│           跳过
│       elif ConfigurationClassUtils.checkConfigurationClassCandidate(beanDef, factory):
│           configCandidates.add(new BeanDefinitionHolder(beanDef, beanName))
│
├─ [Step 2] 没有候选 → return (什么也不做)
│
├─ [Step 3] 按 @Order 排序
│   configCandidates.sort((bd1, bd2) -> getOrder(bd1) vs getOrder(bd2))
│
├─ [Step 4] 检测并设置 BeanNameGenerator
│   从 SingletonBeanRegistry 查找 CONFIGURATION_BEAN_NAME_GENERATOR
│   若存在且本地未设置，则覆写 componentScanBeanNameGenerator 和 importBeanNameGenerator
│
├─ [Step 5] 创建 ConfigurationClassParser 并解析
│   ConfigurationClassParser parser = new ConfigurationClassParser(
│       metadataReaderFactory, problemReporter, environment,
│       resourceLoader, componentScanBeanNameGenerator, registry);
│
│   do {
│       parser.parse(configCandidates);
│   } while (!newCandidates.isEmpty());
│   // 循环：解析过程中 @ComponentScan 可能发现新候选
│
├─ [Step 6] 校验配置类
│   parser.validate();
│   // 检查：FULL 模式类不能是 final、@Bean 方法不能重载
│
├─ [Step 7] 注册 @PropertySource 描述符
│   this.propertySourceDescriptors = parser.getPropertySourceDescriptors();
│
├─ [Step 8] 收集 BeanRegistrar
│
├─ [Step 9] 创建 ConfigurationClassBeanDefinitionReader 并加载
│   reader.loadBeanDefinitions(parser.getConfigurationClasses());
│   // 将解析出的 ConfigurationClass 集合转化为 BeanDefinition 注册
│
├─ [Step 10] 检查是否有新的配置类候选（递归）
│   for each newBeanName in registry:
│       if 新注册的 BeanDefinition 是配置类候选:
│           newCandidates.add(...)
│   // 若有，则回到 Step 5 循环
│
└─ [Step 11] 注册 ImportRegistry (如需要)
    registry.registerSingleton(IMPORT_REGISTRY_BEAN_NAME, parser.getImportRegistry())
```

### 3.3 `postProcessBeanFactory()` — CGLIB 增强入口

在 `invokeBeanFactoryPostProcessors()` 的**第三轮**中被调用（作为 `BeanFactoryPostProcessor`）。

```java
public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    // 1. 确保 processConfigBeanDefinitions 已被调用
    if (!this.registriesPostProcessed.contains(factoryId)) {
        processConfigBeanDefinitions((BeanDefinitionRegistry) beanFactory);
    }

    // 2. 对 FULL 模式配置类进行 CGLIB 增强
    enhanceConfigurationClasses(beanFactory);

    // 3. 注册 ImportAwareBeanPostProcessor
    beanFactory.addBeanPostProcessor(new ImportAwareBeanPostProcessor(beanFactory));
}
```

`enhanceConfigurationClasses()` 对每个被标记为 `CONFIGURATION_CLASS_FULL` 的 BeanDefinition 进行增强，详见第九章。

---

## 四、配置类解析器：ConfigurationClassParser

**源文件**: `spring-context/.../annotation/ConfigurationClassParser.java`

这是配置类解析的核心，**全部基于 ASM 读取字节码**，不加载实际 Class 对象（避免触发不必要的类加载）。

### 4.1 核心字段

```
ConfigurationClassParser
├─ metadataReaderFactory     (ASM MetadataReader 工厂，基于 CachingMetadataReaderFactory)
├─ configurationClasses      (Map<ConfigurationClass, ConfigurationClass> — 解析结果集)
├─ knownSuperclasses         (MultiValueMap — 已处理的父类，防重复)
├─ importStack               (ImportStack extends ArrayDeque — 用于 @Import 循环检测)
├─ deferredImportSelectorHandler  (延迟 ImportSelector 处理器)
├─ conditionEvaluator        (ConditionEvaluator — @Conditional 评估)
├─ componentScanParser       (ComponentScanAnnotationParser — @ComponentScan 解析)
└─ propertySourceRegistry    (PropertySourceRegistry — @PropertySource 处理)
```

### 4.2 `parse()` — 入口方法

```java
public void parse(Set<BeanDefinitionHolder> configCandidates) {
    for (BeanDefinitionHolder holder : configCandidates) {
        BeanDefinition bd = holder.getBeanDefinition();
        // 分三种情况创建 ConfigurationClass 并解析：
        if (bd instanceof AnnotatedBeanDefinition annotatedBeanDef) {
            configClass = parse(annotatedBeanDef, holder.getBeanName());
        } else if (bd instanceof AbstractBeanDefinition abstractBd && abstractBd.hasBeanClass()) {
            configClass = parse(abstractBd.getBeanClass(), holder.getBeanName());
        } else {
            configClass = parse(bd.getBeanClassName(), holder.getBeanName());
        }
        // FULL → LITE 降级：如果没有非静态 @Bean 方法，降级为 LITE
        if (!configClass.getMetadata().isAbstract() && !configClass.hasNonStaticBeanMethods() &&
                CONFIGURATION_CLASS_FULL.equals(bd.getAttribute(...))) {
            bd.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
        }
    }
    // ★ 最后处理所有延迟 ImportSelector
    this.deferredImportSelectorHandler.process();
}
```

三种 `parse()` 重载方法最终都走同一个路径：

```
parse(AnnotatedBeanDefinition / Class / String className, String beanName)
  → new ConfigurationClass(metadata/reader/class, beanName)
  → processConfigurationClass(configClass, DEFAULT_EXCLUSION_FILTER)
```

### 4.3 `processConfigurationClass()` — 条件检查+去重+递归

这是包装 `doProcessConfigurationClass()` 的外层方法：

```
processConfigurationClass(ConfigurationClass configClass, Predicate<String> filter)
│
├─ [Step 1] @Conditional 检查 (PARSE_CONFIGURATION 阶段)
│   if conditionEvaluator.shouldSkip(configClass.getMetadata(),
│                                     ConfigurationPhase.PARSE_CONFIGURATION):
│       return; // 条件不满足，跳过
│
├─ [Step 2] 去重处理
│   existingClass = configurationClasses.get(configClass)
│   if existingClass != null:
│       if configClass.isImported():
│           if existingClass.isImported() → mergeImportedBy() (合并所有引用)
│           else → return (已有的非 Import 类优先)
│       elif configClass.isScanned() → return (扫描的类不覆盖显式导入)
│       else → remove(existingClass) (显式注册覆盖旧的)
│
├─ [Step 3] 递归处理本类及父类层次结构
│   sourceClass = asSourceClass(configClass, filter)
│   do {
│       sourceClass = doProcessConfigurationClass(configClass, sourceClass, filter);
│   } while (sourceClass != null);
│   // doProcessConfigurationClass 返回父类，递归向上处理
│
└─ [Step 4] 存入结果集
    configurationClasses.put(configClass, configClass)
```

### 4.4 `doProcessConfigurationClass()` — 核心解析逻辑

这是实际执行注解解析的方法，按**固定顺序**处理各类注解：

```
doProcessConfigurationClass(ConfigurationClass configClass,
                            SourceClass sourceClass, Predicate<String> filter)
│
├─ [1] 处理成员类 (如果有 @Component)
│   if metadata.isAnnotated(Component.class.getName()):
│       processMemberClasses(configClass, sourceClass, filter)
│       递归处理嵌套的 @Configuration / @Component 内部类
│
├─ [2] 处理 @PropertySource
│   for each @PropertySource / @PropertySources:
│       propertySourceRegistry.processPropertySource(attributes)
│
├─ [3] 处理 @ComponentScan
│   ① 先找直接声明的 @ComponentScan
│   ② 如果没找到，再找元注解上的 @ComponentScan (isMetaPresent)
│   ③ for each @ComponentScan:
│       scannedBeanDefinitions = componentScanParser.parse(componentScan,
│                                      sourceClass.getMetadata().getClassName());
│       // 扫描到的结果中，检查是否有新的配置类
│       for each holder in scannedBeanDefinitions:
│           if checkConfigurationClassCandidate(bdCand, metadataReaderFactory):
│               parse(bdCand.getBeanClassName(), holder.getBeanName());
│               // ★ 递归解析新发现的配置类
│
├─ [4] 处理 @Import
│   imports = getImports(sourceClass)  // 收集所有 Import 注解的 value 数组
│   processImports(configClass, sourceClass, imports, filter, true)
│   (详见第六章)
│
├─ [5] 处理 @ImportResource
│   importResource = attributesFor(importResource)  // 解析 XML 路径
│   configClass.addImportedResource(resolvedResource, readerClass)
│
├─ [6] 收集 @Bean 方法
│   beanMethods = retrieveBeanMethodMetadata(sourceClass)
│   for each methodMetadata:
│       configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass))
│   // 排除 @JvmStatic 的非静态 Kotlin 方法
│
├─ [7] 处理接口默认方法
│   processInterfaces(configClass, sourceClass)
│   收集接口中具有 @Bean 注解的默认方法
│
└─ [8] 处理父类
    if sourceClass.getMetadata().hasSuperClass():
        superclass = sourceClass.getMetadata().getSuperClassName();
        if superclass != null && !superclass.startsWith("java"):
            knownSuperclasses.add(superclass, configClass)
            return sourceClass.getSuperClass();  // ★ 返回父类 SourceClass
    return null;
```

### 4.5 `processMemberClasses()` — 成员类处理

```java
private void processMemberClasses(ConfigurationClass configClass,
        SourceClass sourceClass, Predicate<String> filter) {
    // 1. 获取成员类（内部类）
    Collection<SourceClass> memberClasses = sourceClass.getMemberClasses();
    // 2. 筛选配置类候选
    candidates = memberClasses 中 ConfigurationClassUtils.isConfigurationCandidate 的
    // 3. 按 @Order 排序
    OrderComparator.sort(candidates);
    // 4. 递归处理每个候选成员类
    for each candidate:
        importStack.push(configClass);
        processConfigurationClass(candidate.asConfigClass(configClass), filter);
        importStack.pop();
}
```

### 4.6 SourceClass — 统一抽象

`SourceClass` 是 `ConfigurationClassParser` 的内部类，封装了两种来源的类元数据：

| 来源类型 | 底层对象 | 元数据获取方式 |
|---------|---------|-------------|
| Class 对象 | `Class<?>` | `AnnotationMetadata.introspect(class)` |
| ASM MetadataReader | `MetadataReader` | `reader.getAnnotationMetadata()` |

关键方法：
- `getMetadata()` — 获取 `AnnotationMetadata`
- `getMemberClasses()` — 先尝试反射 `getDeclaredClasses()`，失败则回退到 ASM
- `getSuperClass()` — 获取父类的 `SourceClass`
- `getInterfaces()` — 获取接口的 `SourceClass` 集合
- `asConfigClass(importedBy)` — 转换为 `ConfigurationClass`

---

## 五、@ComponentScan 扫描机制

### 5.1 整体调用链

```
ConfigurationClassParser.doProcessConfigurationClass()
  → componentScanParser.parse(componentScan, declaringClassName)
    → ComponentScanAnnotationParser.parse()
      → ClassPathBeanDefinitionScanner.doScan(basePackages)
        → ClassPathScanningCandidateComponentProvider.findCandidateComponents(basePackage)
          → scanCandidateComponents(basePackage)
```

### 5.2 `ComponentScanAnnotationParser.parse()`

**源文件**: `spring-context/.../annotation/ComponentScanAnnotationParser.java`

```
ComponentScanAnnotationParser.parse(AnnotationAttributes componentScan, String declaringClass)
│
├─ [1] 创建 ClassPathBeanDefinitionScanner
│   new ClassPathBeanDefinitionScanner(registry, useDefaultFilters, environment, resourceLoader)
│   → 如果 useDefaultFilters=true，调用 registerDefaultFilters()
│      添加 @Component + @Named(JSR-330) 的 AnnotationTypeFilter
│
├─ [2] 配置 Scanner 参数 (从 @ComponentScan 注解属性提取)
│   ├─ nameGenerator         (BeanNameGenerator)
│   ├─ scopedProxy / scopeResolver
│   ├─ resourcePattern       (默认 "**/*.class")
│   ├─ includeFilters        (用户自定义 include)
│   ├─ excludeFilters        (用户自定义 exclude)
│   └─ lazyInit
│
├─ [3] 确定扫描包 (basePackages)
│   优先级: basePackages > basePackageClasses > declaringClass 的包
│   ├─ 如果都为空 → 使用 declaringClass 所在的包作为默认扫描包
│   └─ 占位符解析: environment.resolvePlaceholders()
│
├─ [4] 排除声明类自身
│   scanner.addExcludeFilter(matchClassName → declaringClass.equals(className))
│
└─ [5] 执行扫描
    return scanner.doScan(basePackages)
         → 返回 Set<BeanDefinitionHolder>
```

### 5.3 `ClassPathBeanDefinitionScanner.doScan()`

**源文件**: `spring-context/.../annotation/ClassPathBeanDefinitionScanner.java`

```
ClassPathBeanDefinitionScanner.doScan(String... basePackages)
│
├─ for each basePackage:
│   │
│   ├─ [1] findCandidateComponents(basePackage)
│   │   → 调用父类 ClassPathScanningCandidateComponentProvider.findCandidateComponents()
│   │   → 返回 Set<BeanDefinition> (实际是 ScannedGenericBeanDefinition)
│   │
│   ├─ for each candidate:
│   │   ├─ scopeMetadataResolver.resolveScopeMetadata(candidate)
│   │   │   → 解析 @Scope 注解 → 设置 scope (singleton/prototype/...)
│   │   │
│   │   ├─ beanNameGenerator.generateBeanName(candidate, registry)
│   │   │   → AnnotationBeanNameGenerator: 默认用类名首字母小写
│   │   │
│   │   ├─ postProcessBeanDefinition(abstractBeanDefinition, beanName)
│   │   │   → applyDefaults() + autowireCandidatePatterns 匹配
│   │   │
│   │   ├─ processCommonDefinitionAnnotations(annotatedBeanDefinition)
│   │   │   → 处理 @Lazy, @Primary, @DependsOn, @Role, @Description
│   │   │
│   │   ├─ checkCandidate(beanName, candidate)
│   │   │   → 检查是否与已有 BeanDefinition 冲突
│   │   │       ├─ 不存在 → 通过
│   │   │       ├─ 存在且兼容 (同一个类) → 跳过
│   │   │       └─ 存在且不兼容 → 抛出 ConflictingBeanDefinitionException
│   │   │
│   │   ├─ applyScopedProxyMode(scopeMetadata, definitionHolder, registry)
│   │   │   → 如果 scopedProxy != NO，创建 ScopedProxyFactoryBean 包装
│   │   │
│   │   └─ registerBeanDefinition(definitionHolder, registry)
│   │       → BeanDefinitionReaderUtils.registerBeanDefinition()
│   │       → 最终存入 DefaultListableBeanFactory.beanDefinitionMap
│   │
└─ return beanDefinitions
```

### 5.4 `ClassPathScanningCandidateComponentProvider.findCandidateComponents()`

**源文件**: `spring-context/.../annotation/ClassPathScanningCandidateComponentProvider.java`

```
findCandidateComponents(String basePackage)
│
├─ [优先路径] 使用组件索引 (CandidateComponentsIndex)
│   if componentsIndex != null && indexSupportsIncludeFilters():
│       → addCandidateComponentsFromIndex(componentsIndex, basePackage)
│       // 从预计算索引中读取，性能极高，无需扫描 classpath
│
└─ [默认路径] 扫描 classpath: scanCandidateComponents(basePackage)
    │
    ├─ [1] 构建扫描路径
    │   packageSearchPattern = "classpath*:" + basePackage + "/**/*.class"
    │
    ├─ [2] 获取所有匹配的资源
    │   resources = getResourcePatternResolver().getResources(packageSearchPattern)
    │
    ├─ for each resource:
    │   ├─ 跳过 CGLIB 生成的类 (含 "$$" 的 class 文件)
    │   │
    │   ├─ [3] ASM 读取元数据
    │   │   metadataReader = getMetadataReaderFactory().getMetadataReader(resource)
    │   │   // 使用 ASM ClassReader 读取，不加载类
    │   │
    │   ├─ [4] isCandidateComponent(metadataReader) 筛选
    │   │   ├─ 先检查 excludeFilters → 任一匹配则排除
    │   │   ├─ 再检查 includeFilters → 任一匹配则通过
    │   │   └─ isConditionMatch() → @Conditional 检查
    │   │
    │   ├─ [5] isCandidateComponent(sbd) 二次筛选
    │   │   → metadata.isIndependent() && (metadata.isConcrete() || 有 @Lookup)
    │   │   // 必须是独立的顶层/静态内部类，不能是接口或抽象类（除非有 @Lookup）
    │   │
    │   └─ candidates.add(new ScannedGenericBeanDefinition(metadataReader))
    │
    └─ return candidates
```

### 5.5 `registerDefaultFilters()` — 默认过滤器

```java
protected void registerDefaultFilters() {
    // 核心：扫描所有 @Component 注解及其派生注解
    this.includeFilters.add(new AnnotationTypeFilter(Component.class));
    // 派生关系: @Service, @Repository, @Controller 均被 @Component 元注解标记

    // 如果有 JSR-330: 同时扫描 @Named
    this.includeFilters.add(new AnnotationTypeFilter(
        ClassUtils.forName("jakarta.inject.Named", cl), false));
}
```

`AnnotationTypeFilter` 的匹配逻辑包含**元注解查找**：如果一个类被 `@Service` 注解，而 `@Service` 被 `@Component` 元注解，则该类会被匹配到。

### 5.6 @ComponentScan 发现新配置类的递归

```
ConfigurationClassParser.doProcessConfigurationClass()
  → componentScanParser.parse() → 扫描到一批 BeanDefinition
  → for each scanned BD:
      if ConfigurationClassUtils.checkConfigurationClassCandidate(bd):
          parse(bd.getBeanClassName(), beanName);
          → processConfigurationClass() → doProcessConfigurationClass()
          → ★ 递归深入处理新发现的配置类
```

同时，外层 `processConfigBeanDefinitions()` 中有一个 `do-while` 循环：

```
do {
    parser.parse(configCandidates);
    // 检查是否有新注册的配置类候选
    newCandidates = 新找到的候选
} while (!newCandidates.isEmpty());
```

确保多级扫描发现的配置类全部被处理。

---

## 六、@Import 导入机制

### 6.1 `processImports()` — 处理所有 @Import

```
processImports(ConfigurationClass configClass, SourceClass currentSourceClass,
               Collection<SourceClass> importCandidates, Predicate<String> filter,
               boolean checkForCircularImports)
│
├─ [1] 循环导入检测
│   if checkForCircularImports && isChainedImportOnStack(configClass):
│       problemReporter.error(new CircularImportProblem(configClass, importStack))
│       return;
│
├─ for each candidate in importCandidates:
│   │
│   ├─ [情况A] ImportSelector (不是 DeferredImportSelector)
│   │   ├─ 实例化 ImportSelector
│   │   ├─ 如果实现了 Aware 接口，回调 setEnvironment/setResourceLoader/...
│   │   ├─ selectImports(sourceClass.getMetadata()) → 返回 String[] 类名
│   │   ├─ 递归 processImports(configClass, sourceClass, asSourceClasses(strings), filter, false)
│   │   └─ 如果抛出异常但已选了一些类，已选的类照常处理
│   │
│   ├─ [情况B] DeferredImportSelector
│   │   └─ deferredImportSelectorHandler.handle(configClass, selector)
│   │       → 延迟到所有解析完成后才处理
│   │
│   ├─ [情况C] ImportBeanDefinitionRegistrar
│   │   ├─ 实例化 ImportBeanDefinitionRegistrar
│   │   ├─ configClass.addImportBeanDefinitionRegistrar(registrar, metadata)
│   │   └─ ★ 不在此处注册，留到 ConfigurationClassBeanDefinitionReader 中执行
│   │
│   └─ [情况D] 普通配置类（以上都不是）
│       → importStack.registerImport(...)
│       → processConfigurationClass(candidate.asConfigClass(configClass), filter)
│           // 递归处理被导入的配置类
```

### 6.2 ImportSelector vs DeferredImportSelector

| 特性 | ImportSelector | DeferredImportSelector |
|------|---------------|----------------------|
| 执行时机 | **立即**，在解析到 `@Import` 时 | **延迟**，在所有配置类解析完成后 |
| 适用场景 | 普通条件导入 | 需要等待所有配置类解析完成后才能决定的导入（如 `@EnableAutoConfiguration`） |
| 典型实现 | Spring Boot 中各种 `@Enable*` 注解 | `SpringBootApplication` 的 `AutoConfigurationImportSelector` |
| 分组支持 | 否 | 是，通过 `getImportGroup()` 可分组处理 |
| 执行顺序 | 注解声明顺序 | 先排序（按 `@Order`），再按组执行 |

### 6.3 DeferredImportSelector 延迟处理机制

```
deferredImportSelectorHandler.handle(configClass, importSelector)
│
├─ if deferredImportSelectors == null (正在处理中):
│   → 立即创建 DeferredImportSelectorGroupingHandler
│   → handler.register(holder)
│   → handler.processGroupImports()
│       → 分组处理：
│           for each group:
│               group.process(metadata, selector)  // 收集所有待导入类名
│               for each entry in group.selectImports():
│                   processImports(configClass, sourceClass,
│                                  asSourceClass(entry.importClassName), filter, false)
│
└─ if deferredImportSelectors != null (收集阶段):
    → deferredImportSelectors.add(new DeferredImportSelectorHolder(...))
    → 等待 parse() 末尾调用 deferredImportSelectorHandler.process() 统一处理
```

### 6.4 循环导入检测

`ImportStack` 是一个 `ArrayDeque<ConfigurationClass>`，同时在内部维护了一个 `MultiValueMap<String, AnnotationMetadata>`：

- `push()` — 进入一个导入处理时入栈
- `pop()` — 离开时出栈
- `isChainedImportOnStack()` — 检查当前类在整个导入链中是否已出现过（不仅看栈顶，而是遍历整个链）

```
示例: A imports B imports C imports A
→ 当 C 试图导入 A 时，发现 A 已经在导入链中 → 抛出 CircularImportProblem
```

---

## 七、@Bean 方法注册：ConfigurationClassBeanDefinitionReader

**源文件**: `spring-context/.../annotation/ConfigurationClassBeanDefinitionReader.java`

在 `ConfigurationClassParser` 完成解析后，`ConfigurationClassBeanDefinitionReader` 将解析出的 `ConfigurationClass` 集合转化为 BeanDefinition 并注册到容器。

### 7.1 `loadBeanDefinitions()` 入口

```java
public void loadBeanDefinitions(Set<ConfigurationClass> configurationModel) {
    TrackedConditionEvaluator trackedConditionEvaluator = new TrackedConditionEvaluator();
    for (ConfigurationClass configClass : configurationModel) {
        loadBeanDefinitionsForConfigurationClass(configClass, trackedConditionEvaluator);
    }
}
```

### 7.2 `loadBeanDefinitionsForConfigurationClass()` — 逐类加载

```
loadBeanDefinitionsForConfigurationClass(ConfigurationClass configClass,
                                         TrackedConditionEvaluator evaluator)
│
├─ [Step 1] @Conditional 检查 (REGISTER_BEAN 阶段)
│   if evaluator.shouldSkip(configClass):
│       移除之前注册的同名 BeanDefinition
│       移除 importRegistry 中的记录
│       return;
│
├─ [Step 2] 如果该类是被 @Import 导入的 → 注册其自身的 BeanDefinition
│   registerBeanDefinitionForImportedConfigurationClass(configClass)
│   → 用 importBeanNameGenerator 生成 beanName (默认全限定类名)
│   → 注册 AnnotatedGenericBeanDefinition
│
├─ [Step 3] 注册 @Bean 方法
│   for each beanMethod in configClass.getBeanMethods():
│       loadBeanDefinitionsForBeanMethod(beanMethod)
│
├─ [Step 4] 加载 @ImportResource 指向的 XML/Groovy 资源
│   loadBeanDefinitionsFromImportedResources(configClass.getImportedResources())
│
├─ [Step 5] 执行 ImportBeanDefinitionRegistrar
│   loadBeanDefinitionsFromImportBeanDefinitionRegistrars(
│       configClass.getImportBeanDefinitionRegistrars())
│
└─ [Step 6] 执行 BeanRegistrar
    loadBeanDefinitionsFromBeanRegistrars(configClass.getBeanRegistrars())
```

### 7.3 `loadBeanDefinitionsForBeanMethod()` — @Bean 方法注册细节

```
loadBeanDefinitionsForBeanMethod(BeanMethod beanMethod)
│
├─ [1] 条件检查 (REGISTER_BEAN 阶段)
│   if conditionEvaluator.shouldSkip(metadata, ConfigurationPhase.REGISTER_BEAN):
│       configClass.skippedBeanMethods.add(methodName)
│       return;
│
├─ [2] 确定 Bean 名称
│   explicitNames = @Bean 的 name 属性数组
│   beanName = explicitNames[0] (或默认用方法名)
│   注册别名: explicitNames[1..n] → registry.registerAlias(beanName, alias)
│
├─ [3] 检查是否已被已有定义覆盖
│   isOverriddenByExistingDefinition(beanMethod, beanName, beanDef)
│   ├─ 已有定义是同名的 ConfigurationClassBeanDefinition:
│   │   ├─ 同方法名 → 标记为 nonUniqueFactoryMethodName (重载情况)
│   │   └─ 不同方法名 + enforceUniqueMethods=false → 保留已有 (允许覆盖)
│   ├─ 已有定义是 ScannedGenericBeanDefinition:
│   │   └─ 同类型 → 移除扫描的定义，用 @Bean 定义覆盖
│   └─ 其他情况 → 抛出 BeanDefinitionOverrideException
│
├─ [4] 区分静态/实例 @Bean 方法
│   ├─ static 方法:
│   │   ├─ beanDef.setBeanClass(configClass)
│   │   └─ beanDef.setUniqueFactoryMethodName(methodName)
│   └─ 实例方法:
│       ├─ beanDef.setFactoryBeanName(configClass.getBeanName())
│       └─ beanDef.setUniqueFactoryMethodName(methodName)
│       // ★ 实例方法通过 factory-bean + factory-method 创建 bean
│
├─ [5] 设置自动装配模式
│   beanDef.setAutowireMode(AUTOWIRE_CONSTRUCTOR)
│   // @Bean 方法支持构造器注入
│
├─ [6] 处理通用注解
│   processCommonDefinitionAnnotations(beanDef, metadata)
│   → @Lazy, @Primary, @DependsOn, @Role, @Description
│
├─ [7] 处理 @Scope 和 ScopedProxy
│   如果有 @Scope → 设置 scope + ScopedProxyMode
│   如果需要代理 → 创建 ScopedProxyCreator.createScopedProxy()
│
├─ [8] 其他属性
│   ├─ autowireCandidate
│   ├─ defaultCandidate
│   ├─ bootstrap (Lazy/BACKGROUND)
│   ├─ initMethod / destroyMethod
│
└─ [9] 注册
    registry.registerBeanDefinition(beanName, beanDefToRegister)
```

### 7.4 静态 @Bean vs 实例 @Bean

对于 `@Bean` 方法创建的 BeanDefinition，根 bean class 和实现 bean class 的关系如下：

| @Bean 类型 | BeanDefinition 设置 | 实际创建方式 |
|-----------|-------------------|------------|
| 实例方法 | `factoryBeanName=configClassName` + `factoryMethodName=methodName` | 先获取配置类实例，再调用其工厂方法 |
| 静态方法 | `beanClassName=configClassName` + `factoryMethodName=methodName` | 直接调用静态工厂方法，不需要配置类实例 |

### 7.5 TrackedConditionEvaluator — 条件追踪

内部类 `TrackedConditionEvaluator` 缓存每个 `ConfigurationClass` 的条件评估结果：

```java
private class TrackedConditionEvaluator {
    private final Map<ConfigurationClass, Boolean> skipped = new HashMap<>();

    public boolean shouldSkip(ConfigurationClass configClass) {
        Boolean skip = this.skipped.get(configClass);
        if (skip == null) {
            if (configClass.isImported()) {
                // 所有导入它的类都被跳过 → 它也跳过
                boolean allSkipped = true;
                for (ConfigurationClass importedBy : configClass.getImportedBy()) {
                    if (!shouldSkip(importedBy)) { allSkipped = false; break; }
                }
                if (allSkipped) skip = true;
            }
            if (skip == null) {
                skip = conditionEvaluator.shouldSkip(
                    configClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN);
            }
            this.skipped.put(configClass, skip);
        }
        return skip;
    }
}
```

这保证了：如果一个被 `@Import` 导入的配置类，其所有的"导入者"都因条件不满足被跳过，则该类也被跳过。

---

## 八、@Conditional 条件评估：ConditionEvaluator

**源文件**: `spring-context/.../annotation/ConditionEvaluator.java`

### 8.1 `shouldSkip()` — 评估入口

```
shouldSkip(AnnotatedTypeMetadata metadata, ConfigurationPhase phase)
│
├─ if metadata == null || 没有 @Conditional 注解 → return false (不跳过)
│
├─ if phase == null (自动推断):
│   if isConfigurationCandidate(metadata):
│       → shouldSkip(metadata, PARSE_CONFIGURATION)
│   else:
│       → shouldSkip(metadata, REGISTER_BEAN)
│
├─ [正式评估] collectConditions(metadata)
│   → 从 @Conditional 注解中提取所有 Condition 实现类
│   → 实例化并按 @Order 排序
│
├─ for each condition:
│   requiredPhase = (condition instanceof ConfigurationCondition cc)
│                   ? cc.getConfigurationPhase() : null
│
│   if (requiredPhase == null || requiredPhase == phase):
│       if !condition.matches(context, metadata):
│           return true;  // ★ 任一不匹配则跳过
│
└─ return false;  // 所有条件都匹配，不跳过
```

### 8.2 两种评估阶段

| 阶段 | 枚举值 | 触发位置 | 说明 |
|------|-------|---------|------|
| PARSE_CONFIGURATION | `ConfigurationPhase.PARSE_CONFIGURATION` | `ConfigurationClassParser.processConfigurationClass()` | 解析前判断整个配置类是否应被跳过 |
| REGISTER_BEAN | `ConfigurationPhase.REGISTER_BEAN` | `ConfigurationClassBeanDefinitionReader` | 注册前判断 @Bean 方法是否应被跳过 |

`ConfigurationCondition` 可以通过 `getConfigurationPhase()` 限定条件仅在某一阶段生效。普通的 `Condition` 在两个阶段都会评估。

### 8.3 ConditionContextImpl — 条件上下文

```java
private static class ConditionContextImpl implements ConditionContext {
    private final BeanDefinitionRegistry registry;   // BeanDefinition 注册表
    private final ConfigurableListableBeanFactory beanFactory;  // Bean 工厂
    private final Environment environment;           // 环境
    private final ResourceLoader resourceLoader;     // 资源加载器
    private final ClassLoader classLoader;           // 类加载器
}
```

这为 `Condition.matches()` 提供了查询容器状态的能力，用于实现如 `@ConditionalOnClass`、`@ConditionalOnMissingBean` 等条件。

---

## 九、配置类 CGLIB 增强：ConfigurationClassEnhancer

**源文件**: `spring-context/.../annotation/ConfigurationClassEnhancer.java`

### 9.1 触发时机

在 `ConfigurationClassPostProcessor.postProcessBeanFactory()` 中调用 `enhanceConfigurationClasses()`：

```java
public void enhanceConfigurationClasses(ConfigurableListableBeanFactory beanFactory) {
    for (String beanName : beanFactory.getBeanDefinitionNames()) {
        BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
        // 仅对 FULL 模式配置类进行增强
        if (CONFIGURATION_CLASS_FULL.equals(
                beanDef.getAttribute(CONFIGURATION_CLASS_ATTRIBUTE))) {
            Class<?> configClass = beanDef.getBeanClass();
            // 用 CGLIB 创建增强子类
            Class<?> enhancedClass = new ConfigurationClassEnhancer()
                    .enhance(configClass, beanFactory.getBeanClassLoader());
            beanDef.setBeanClass(enhancedClass);
        }
    }
}
```

### 9.2 增强原理

`ConfigurationClassEnhancer` 使用 CGLIB 的 `Enhancer` 创建动态子类：

```
原始: @Configuration class AppConfig { @Bean A a() { ... }; @Bean B b() { return new B(a()); } }

增强后:
class AppConfig$$SpringCGLIB$$0 extends AppConfig {
    // 覆写 @Bean 方法
    @Override A a() {
        // 检查容器中是否已有 bean "a"
        if (beanFactory.containsSingleton("a")) {
            return beanFactory.getBean("a");  // ★ 从容器取，保证单例
        }
        return super.a();  // 调用父类原方法创建
    }
}
```

增强了每次 `@Bean` 方法调用都会先检查容器，确保即使方法间相互调用，获取的也是同一个单例 bean。

### 9.3 增强后序列化支持

如果 `@Configuration` 类实现了 `Serializable`，增强子类也会配置 `serialVersionUID`。同时通过 `Enhancer.registerStaticCallbacks()` 注册静态回调数组，使序列化后的增强类依然可以正常工作。

---

## 十、完整调用链总结

### 10.1 从 `AnnotationConfigApplicationContext` 到配置类 BeanDefinition 注册

```
new AnnotationConfigApplicationContext(AppConfig.class)
│
├─ 构造函数:
│   ├─ AnnotatedBeanDefinitionReader(this)
│   │   └─ registerAnnotationConfigProcessors(registry)
│   │       注册: ConfigurationClassPostProcessor, AutowiredAnnotationBPP,
│   │             CommonAnnotationBPP, PersistenceAnnotationBPP,
│   │             EventListenerMethodProcessor, DefaultEventListenerFactory
│   │
│   ├─ ClassPathBeanDefinitionScanner(this)
│   │   └─ registerDefaultFilters() → 添加 @Component 过滤器
│   │
│   └─ register(AppConfig.class)
│       └─ AnnotatedGenericBeanDefinition → beanDefinitionMap.put("appConfig", bd)
│
└─ refresh()
    │
    ├─ ... step 1-4 (prepareRefresh, obtainFreshBeanFactory, prepareBeanFactory, postProcessBeanFactory)
    │
    ├─ [step 5] invokeBeanFactoryPostProcessors(beanFactory)
    │   │
    │   ├─ [Round 1] PriorityOrdered BDRegistryPostProcessor
    │   │   └─ ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry(registry)
    │   │       └─ processConfigBeanDefinitions(registry)
    │   │           │
    │   │           ├─ Step A: 检测候选
    │   │           │   for each BD: ConfigurationClassUtils.checkConfigurationClassCandidate(bd)
    │   │           │   → AppConfig 被标记为 CONFIGURATION_CLASS_FULL
    │   │           │
    │   │           ├─ Step B: 排序
    │   │           │
    │   │           ├─ Step C: 解析
    │   │           │   ConfigurationClassParser.parse({AppConfig})
    │   │           │   │
    │   │           │   ├─ processConfigurationClass(AppConfig)
    │   │           │   │   └─ doProcessConfigurationClass(AppConfig)
    │   │           │   │       ├─ @ComponentScan → 扫描 com.example 包
    │   │           │   │       │   → 发现 UserService, OrderRepository 等
    │   │           │   │       │   → 注册 ScannedGenericBeanDefinition
    │   │           │   │       │   → 检查是否有新的配置类 → 递归处理
    │   │           │   │       ├─ @Import → 处理导入的配置类
    │   │           │   │       │   → 如果是 ImportSelector → selectImports() → 递归
    │   │           │   │       │   → 如果是 DeferredImportSelector → 放入延迟队列
    │   │           │   │       │   → 如果是 ImportBeanDefinitionRegistrar → 收集
    │   │           │   │       │   → 否则作为普通配置类 → processConfigurationClass()
    │   │           │   │       ├─ @Bean 方法 → configClass.addBeanMethod(...)
    │   │           │   │       ├─ 接口默认方法
    │   │           │   │       └─ 父类递归
    │   │           │   │
    │   │           │   └─ deferredImportSelectorHandler.process()
    │   │           │       → 分组处理所有延迟 ImportSelector
    │   │           │
    │   │           ├─ Step D: 校验
    │   │           │   parser.validate()
    │   │           │
    │   │           ├─ Step E: 注册
    │   │           │   ConfigurationClassBeanDefinitionReader.loadBeanDefinitions(
    │   │           │       parser.getConfigurationClasses())
    │   │           │   │
    │   │           │   ├─ for each ConfigurationClass:
    │   │           │   │   ├─ REGISTER_BEAN 条件检查
    │   │           │   │   ├─ @Import 导入的类 → 注册 AnnotatedGenericBeanDefinition
    │   │           │   │   ├─ @Bean 方法 → 注册 ConfigurationClassBeanDefinition
    │   │           │   │   ├─ @ImportResource → 加载 XML
    │   │           │   │   └─ ImportBeanDefinitionRegistrar → registerBeanDefinitions()
    │   │           │   │
    │   │           │   └─ 所有 BeanDefinition 现已注册完成
    │   │           │
    │   │           └─ Step F: 循环检查新候选
    │   │
    │   ├─ [Round 2] Ordered BDRegistryPostProcessor
    │   ├─ [Round 3] 其余 BDRegistryPostProcessor
    │   ├─ [Round 4] PriorityOrdered BFPP
    │   ├─ [Round 5] Ordered BFPP
    │   └─ [Round 6] 其余 BFPP
    │       └─ ConfigurationClassPostProcessor.postProcessBeanFactory(beanFactory)
    │           ├─ enhanceConfigurationClasses() → CGLIB 增强 FULL 配置类
    │           └─ addBeanPostProcessor(ImportAwareBeanPostProcessor)
    │
    └─ ... step 6-12 (registerBeanPostProcessors, initMessageSource, ..., finishRefresh)
```

### 10.2 关键决策点速查

| 决策点 | 位置 | 说明 |
|-------|------|------|
| 是否是配置类候选 | `ConfigurationClassUtils.checkConfigurationClassCandidate()` | 决定是否进入配置类解析流程 |
| FULL vs LITE | 同上 | 决定是否进行 CGLIB 增强 |
| 是否跳过（条件不满足） | `ConditionEvaluator.shouldSkip()` × 2 阶段 | PARSE_CONFIGURATION + REGISTER_BEAN |
| 配置类去重/合并 | `ConfigurationClassParser.processConfigurationClass()` | Imported / Scanned / Explicit 优先级 |
| @ComponentScan 是否递归 | `doProcessConfigurationClass()` | 扫描结果中发现配置类 → 递归 parse() |
| @Bean 方法是否被覆盖 | `isOverriddenByExistingDefinition()` | 扫描定义 vs 显式定义的优先级 |
| 循环导入检测 | `isChainedImportOnStack()` | 沿 ImportStack 链检查 |

---

## 附录：关键源文件索引

| 类名 | 文件路径 | 说明 |
|------|---------|------|
| `ConfigurationClassPostProcessor` | `spring-context/.../annotation/ConfigurationClassPostProcessor.java` | 配置类处理入口 BFPP |
| `ConfigurationClassUtils` | `spring-context/.../annotation/ConfigurationClassUtils.java` | 配置类候选检测工具 |
| `ConfigurationClassParser` | `spring-context/.../annotation/ConfigurationClassParser.java` | 配置类解析器（基于 ASM） |
| `ConfigurationClass` | `spring-context/.../annotation/ConfigurationClass.java` | 配置类模型 |
| `ConfigurationClassBeanDefinitionReader` | `spring-context/.../annotation/ConfigurationClassBeanDefinitionReader.java` | 配置类 BD 注册器 |
| `ComponentScanAnnotationParser` | `spring-context/.../annotation/ComponentScanAnnotationParser.java` | @ComponentScan 解析器 |
| `ClassPathBeanDefinitionScanner` | `spring-context/.../annotation/ClassPathBeanDefinitionScanner.java` | Classpath Bean 扫描器 |
| `ClassPathScanningCandidateComponentProvider` | `spring-context/.../annotation/ClassPathScanningCandidateComponentProvider.java` | 候选组件查找器 |
| `ConditionEvaluator` | `spring-context/.../annotation/ConditionEvaluator.java` | @Conditional 条件评估器 |
| `ConfigurationClassEnhancer` | `spring-context/.../annotation/ConfigurationClassEnhancer.java` | CGLIB 配置类增强器 |
| `AnnotationConfigUtils` | `spring-context/.../annotation/AnnotationConfigUtils.java` | 注解配置工具（通用注解处理） |
| `AnnotatedBeanDefinitionReader` | `spring-context/.../annotation/AnnotatedBeanDefinitionReader.java` | 注解 BD 读取器（初始注册） |

---

> 文档生成日期：2026-06-12
> 源码版本：Spring Framework 7.x
> 基于源码路径：`D:\Spring-Framework\`
> 配套文档：[Spring-IoC-AOP-分析文档.md](Spring-IoC-AOP-分析文档.md)
