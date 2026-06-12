<!-- Spring MVC Source Analysis - Spring Framework 7.x -->

# Spring MVC 请求处理流程分析

> 基于 Spring Framework 7.x 源码，逐方法追踪完整调用链

---

## 一、概述

Spring MVC 基于前端控制器模式，DispatcherServlet 作为中央调度器。

请求处理总流程：
```
HTTP Request -> DispatcherServlet.doDispatch()
  |-- getHandler() -> HandlerMapping -> HandlerExecutionChain
  |-- getHandlerAdapter() -> HandlerAdapter
  |-- ha.handle() -> 参数解析 -> Controller -> 返回值处理
  |-- processDispatchResult() -> render() 或 异常处理
```

核心接口：
```
HandlerMapping -> getHandler -> HandlerExecutionChain
  |-- AbstractHandlerMethodMapping
      |-- RequestMappingHandlerMapping (@RequestMapping)
HandlerAdapter -> supports + handle
  |-- AbstractHandlerMethodAdapter
      |-- RequestMappingHandlerAdapter
          |-- ArgumentResolver (~25个)
          |-- ReturnValueHandler (~15个)
ViewResolver -> resolveViewName -> View
  |-- InternalResourceViewResolver (JSP)
```

---

## 二、DispatcherServlet 初始化

**源文件**: spring-webmvc/.../servlet/DispatcherServlet.java

initStrategies() 初始化九大策略组件：

| 方法 | 组件 | 默认值 |
|------|------|--------|
| initMultipartResolver | MultipartResolver | null |
| initLocaleResolver | LocaleResolver | AcceptHeaderLocaleResolver |
| initHandlerMappings | HandlerMapping列表 | RequestMappingHandlerMapping |
| initHandlerAdapters | HandlerAdapter列表 | RequestMappingHandlerAdapter |
| initHandlerExceptionResolvers | HandlerExceptionResolver列表 | ExceptionHandlerExceptionResolver等 |
| initRequestToViewNameTranslator | RequestToViewNameTranslator | DefaultRequestToViewNameTranslator |
| initViewResolvers | ViewResolver列表 | InternalResourceViewResolver |
| initFlashMapManager | FlashMapManager | SessionFlashMapManager |

默认策略来自 DispatcherServlet.properties：

```properties
HandlerMapping=BeanNameUrlHandlerMapping,RequestMappingHandlerMapping
HandlerAdapter=HttpRequestHandlerAdapter,SimpleControllerHandlerAdapter,RequestMappingHandlerAdapter
HandlerExceptionResolver=ExceptionHandlerExceptionResolver,ResponseStatusExceptionResolver,DefaultHandlerExceptionResolver
ViewResolver=InternalResourceViewResolver
```

---

## 三、doDispatch()：核心请求处理

**源文件**: DispatcherServlet.java (第555行)

所有HTTP请求经过此方法：

```
doDispatch(HttpServletRequest request, HttpServletResponse response)
|
|-- [1] checkMultipart(request) -> 文件上传包装
|
|-- [2] mappedHandler = getHandler(processedRequest)
|    -> 遍历 HandlerMapping 列表
|    -> null -> noHandlerFound() -> 404
|
|-- [3] if !mappedHandler.applyPreHandle(request, response): return
|    拦截器 preHandle 返回 false -> 请求终止
|
|-- [4] HandlerAdapter ha = getHandlerAdapter(handler)
|    -> 找到 supports(handler) 的适配器
|
|-- [5] mv = ha.handle(request, response, handler)
|    -> invokeHandlerMethod()
|    -> 参数解析 -> Controller -> 返回值处理
|
|-- [6] async检测 -> isConcurrentHandlingStarted()? -> return
|
|-- [7] applyDefaultViewName() -> mv无viewName -> 根据URL推断
|
|-- [8] mappedHandler.applyPostHandle(request, response, mv)
|
|-- [9] processDispatchResult(request, response, mappedHandler, mv, exception)
|    |-- 有异常 -> processHandlerException() -> HandlerExceptionResolver
|    |-- 有ModelAndView -> render() -> ViewResolver -> View.render()
|    |-- triggerAfterCompletion()
|
|-- [10] finally: cleanup multipart
```

---

## 四、HandlerMapping：处理器映射

**源文件**: spring-webmvc/.../handler/AbstractHandlerMethodMapping.java

初始化：afterPropertiesSet() -> initHandlerMethods()

```
for each beanName in ApplicationContext:
  processCandidateBean(beanName)
    |-- beanType = context.getType(beanName)
    |-- if isHandler(beanType):  // @Controller?
        |-- detectHandlerMethods(beanName)
            |-- for each method:
                T mapping = getMappingForMethod(method, handlerType)
                // RequestMappingHandlerMapping: 检查 @RequestMapping
                if mapping != null:
                    registerHandlerMethod(handler, method, mapping)
                    -> mappingRegistry.register(mapping, handler, method)
```

MappingRegistry 注册表：

```java
class MappingRegistry {
  // 核心存储: Mapping -> HandlerMethod
  Map<T, MappingRegistration<T>> registry;
  // 直接路径索引
  MultiValueMap<String, T> pathLookup;
  // 读写锁
  ReentrantReadWriteLock readWriteLock;
}
```

请求匹配 lookupHandlerMethod()：

```
|-- [1] 快速精确匹配: pathLookup 中查找
|-- [2] 全量模式匹配 (精确匹配为空时)
|-- [3] 最佳匹配: matches.sort(comparator), 检测歧义
|-- [4] return bestMatch.getHandlerMethod()
```

RequestMappingHandlerMapping:

```java
// isHandler: 有 @Controller 注解?
protected boolean isHandler(Class<?> beanType) {
    return hasAnnotation(beanType, Controller.class);
}
// getMappingForMethod: 创建 RequestMappingInfo
protected RequestMappingInfo getMappingForMethod(Method m, Class<?> handlerType) {
    RequestMappingInfo info = createRequestMappingInfo(m);     // 方法级
    if (info != null) {
        RequestMappingInfo typeInfo = createRequestMappingInfo(handlerType); // 类级
        if (typeInfo != null) info = typeInfo.combine(info);  // 合并
    }
    return info;
}
```

RequestMappingInfo.getMatchingCondition(request) 检查：
URL路径 / HTTP方法 / 请求参数 / 请求头 / Content-Type / Accept

---

## 五、HandlerAdapter：处理器适配

**源文件**: spring-webmvc/.../annotation/RequestMappingHandlerAdapter.java

handleInternal() -> invokeHandlerMethod()：

```
|-- [1] binderFactory = getDataBinderFactory(handlerMethod)
|    -> 收集 @InitBinder 方法
|-- [2] modelFactory = getModelFactory(handlerMethod, binderFactory)
|    -> 收集 @ModelAttribute 方法
|-- [3] 创建 ServletInvocableHandlerMethod + 设置Resolvers
|-- [4] mavContainer = new ModelAndViewContainer()
|    modelFactory.initModel()  // 执行所有 @ModelAttribute
|-- [5] invocableMethod.invokeAndHandle(webRequest, mavContainer)
|    |-- returnValue = invokeForRequest(webRequest, mavContainer)
|    |    |-- args = getMethodArgumentValues() // 参数解析
|    |    |-- doInvoke(args) // method.invoke()
|    |-- setResponseStatus() // @ResponseStatus
|    |-- returnValueHandlers.handleReturnValue() // 返回值处理
|-- [6] return getModelAndView()
```

---

## 六、参数解析：ArgumentResolver

**源文件**: spring-web/.../support/InvocableHandlerMethod.java

getMethodArgumentValues()：

```
for each MethodParameter:
  for each resolver in argumentResolvers:
    if resolver.supportsParameter(parameter):
      return resolver.resolveArgument(param, mavContainer, request, factory)
```

默认参数解析器 (约25个)：

| 解析器 | 处理 |
|--------|------|
| RequestParamMethodArgumentResolver | @RequestParam |
| PathVariableMethodArgumentResolver | @PathVariable |
| RequestResponseBodyMethodProcessor | @RequestBody JSON/XML |
| RequestHeaderMethodArgumentResolver | @RequestHeader |
| ServletCookieValueMethodArgumentResolver | @CookieValue |
| SessionAttributeMethodArgumentResolver | @SessionAttribute |
| ExpressionValueMethodArgumentResolver | @Value |
| ModelAttributeMethodProcessor | @ModelAttribute 对象 |
| ServletRequestMethodArgumentResolver | HttpServletRequest等 |
| ServletResponseMethodArgumentResolver | HttpServletResponse等 |
| ErrorsMethodArgumentResolver | Errors/BindingResult |

---

## 七、返回值处理：ReturnValueHandler

**源文件**: spring-webmvc/.../annotation/ServletInvocableHandlerMethod.java

handleReturnValue()：

```
for each handler in returnValueHandlers:
  if handler.supportsReturnType(returnType):
    handler.handleReturnValue(returnValue, returnType, mavContainer, webRequest)
```

默认返回值处理器 (约15个)：

| 处理器 | 处理 |
|--------|------|
| ModelAndViewMethodReturnValueHandler | ModelAndView |
| RequestResponseBodyMethodProcessor | @ResponseBody / ResponseEntity |
| ViewNameMethodReturnValueHandler | String -> 视图名 |
| CallableMethodReturnValueHandler | Callable -> 异步 |
| DeferredResultMethodReturnValueHandler | DeferredResult -> 异步 |
| ModelAttributeMethodProcessor | @ModelAttribute (兜底) |

@ResponseBody 处理链：

```
RequestResponseBodyMethodProcessor.handleReturnValue()
  -> writeWithMessageConverters()
    -> [1] 选择媒体类型 (Accept + produces)
    -> [2] 选择 HttpMessageConverter
       -> MappingJackson2HttpMessageConverter: ObjectMapper.writeValue()
       -> StringHttpMessageConverter: resp.getWriter().write()
    -> [3] ResponseBodyAdvice 拦截 (可选)
    -> [4] converter.write()
```

---

## 八、视图解析与渲染：render()

**源文件**: DispatcherServlet.render()

```
render(ModelAndView mv, HttpServletRequest req, HttpServletResponse resp)
|-- [1] locale = localeResolver.resolveLocale(req), resp.setLocale()
|-- [2] 解析视图:
|    if viewName != null:
|        for each ViewResolver:
|            View v = resolver.resolveViewName(viewName, locale)
|            if v != null: return v
|-- [3] resp.setStatus(status)
|-- [4] view.render(model, req, resp)
```

InternalResourceViewResolver (JSP)：

```
resolveViewName("hello", locale) -> new InternalResourceView()
  url = "/WEB-INF/" + "hello" + ".jsp" = "/WEB-INF/hello.jsp"
render(): exposeModelAsRequestAttributes() + RequestDispatcher.forward()
```

---

## 九、异常处理

processHandlerException() 遍历 HandlerExceptionResolver：

```
for each resolver:
  exMv = resolver.resolveException(req, resp, handler, ex)
  if exMv != null: break
if exMv != null: return exMv (error view)
else: throw ex (到Servlet容器)
```

ExceptionHandlerExceptionResolver 处理 @ExceptionHandler：

```
|-- 查找 @ExceptionHandler方法 (Controller内部 + @ControllerAdvice)
|-- 按异常类型匹配 (精确匹配 -> 继承关系)
|-- 调用: invocableMethod.invokeAndHandle(webRequest, mavContainer, exception)
```

三种默认异常解析器：

| 解析器 | 处理 |
|-------|------|
| ExceptionHandlerExceptionResolver | @ExceptionHandler |
| ResponseStatusExceptionResolver | @ResponseStatus |
| DefaultHandlerExceptionResolver | 内部异常 (404/400) |

---

## 十、拦截器：HandlerInterceptor

```java
public interface HandlerInterceptor {
    boolean preHandle(req, resp, handler);     // Handler前
    void postHandle(req, resp, handler, mv);   // Handler后, 视图前
    void afterCompletion(req, resp, handler, ex); // 视图后
}
```

执行顺序：

```
preHandle(0) -> preHandle(1) -> Handler
  -> postHandle(1) -> postHandle(0) (倒序)
  -> View
  -> afterCompletion(1) -> afterCompletion(0) (倒序)
```

HandlerExecutionChain.applyPreHandle()：

```java
for (int i = 0; i < size; i++) {
    if (!interceptors[i].preHandle()) {
        triggerAfterCompletion(...); // 回调已执行的
        return false;
    }
    interceptorIndex = i;
}
```

---

## 十一、自动配置：WebMvcConfigurationSupport

**源文件**: spring-webmvc/.../config/annotation/WebMvcConfigurationSupport.java

关键Bean：

```
@Bean RequestMappingHandlerMapping requestMappingHandlerMapping()
@Bean RequestMappingHandlerAdapter requestMappingHandlerAdapter()
  -> 注册约25个ArgumentResolver + 15个ReturnValueHandler
@Bean ExceptionHandlerExceptionResolver handlerExceptionResolver()
@Bean FormattingConversionService conversionService()
```

@EnableWebMvc:

```
@EnableWebMvc -> @Import(DelegatingWebMvcConfiguration.class)
  -> extends WebMvcConfigurationSupport
  -> 注入 WebMvcConfigurer beans -> 委托给用户配置
```

---

## 十二、完整调用链

一个 @ResponseBody GET请求：

```
GET /users/123

DispatcherServlet.doDispatch()
|
|-- getHandler() -> RequestMappingHandlerMapping
|   -> lookupHandlerMethod("/users/123")
|     -> URL匹配 + GET匹配 -> UsersController.getUser(@PathVariable Long id)
|   -> HandlerExecutionChain{handler, interceptors}
|
|-- applyPreHandle() -> 拦截器链
|
|-- getHandlerAdapter() -> RequestMappingHandlerAdapter
|
|-- ha.handle() -> invokeHandlerMethod()
|   |-- invokeAndHandle()
|       |-- invokeForRequest():
|       |   |-- id (@PathVariable Long) -> PathVariableMethodArgumentResolver
|       |   |   -> Long.valueOf("123") -> 123L
|       |   |-- method.invoke(usersController, 123L)
|       |       -> User{id=123, name="张三"}
|       |
|       |-- handleReturnValue():
|           |-- @ResponseBody -> RequestResponseBodyMethodProcessor
|               |-- writeWithMessageConverters(User, json)
|                   |-- Jackson.write(User) -> '{"id":123,"name":"张三"}'
|
|-- processDispatchResult() -> mv==null -> 跳过render
|
|-- HTTP 200, Content-Type: application/json
    {"id":123,"name":"张三"}
```

关键决策点：

| 决策点 | 机制 |
|-------|------|
| 找Handler | 按顺序遍历 HandlerMapping |
| 找Adapter | adapter.supports(handler) |
| 参数解析 | 按顺序遍历 ArgumentResolver |
| 返回值 | 按顺序遍历 ReturnValueHandler |
| 异常 | 按顺序遍历 HandlerExceptionResolver |
| 视图 | ViewResolver -> View.render() |

关键源文件：

| 类 | 路径 |
|----|------|
| DispatcherServlet | spring-webmvc/.../servlet/DispatcherServlet.java |
| AbstractHandlerMethodMapping | spring-webmvc/.../handler/AbstractHandlerMethodMapping.java |
| RequestMappingHandlerMapping | spring-webmvc/.../annotation/RequestMappingHandlerMapping.java |
| RequestMappingHandlerAdapter | spring-webmvc/.../annotation/RequestMappingHandlerAdapter.java |
| InvocableHandlerMethod | spring-web/.../support/InvocableHandlerMethod.java |
| ServletInvocableHandlerMethod | spring-webmvc/.../annotation/ServletInvocableHandlerMethod.java |
| HandlerInterceptor | spring-webmvc/.../HandlerInterceptor.java |
| WebMvcConfigurationSupport | spring-webmvc/.../config/annotation/WebMvcConfigurationSupport.java |

---

> 文档生成日期：2026-06-12 | 源码版本：Spring Framework 7.x
> 配套文档：Spring-IoC-AOP-分析文档.md | Spring-配置类扫描与解析-分析文档.md | Spring-事务传播机制-分析文档.md
