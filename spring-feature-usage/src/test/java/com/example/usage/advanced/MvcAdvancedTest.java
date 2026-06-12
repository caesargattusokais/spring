package com.example.usage.advanced;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.annotation.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@interface CurrentUser {}

class CurrentUserResolver implements HandlerMethodArgumentResolver {
    @Override public boolean supportsParameter(MethodParameter p) {
        return p.hasParameterAnnotation(CurrentUser.class); }
    @Override public Object resolveArgument(MethodParameter p, ModelAndViewContainer mav,
            NativeWebRequest req, WebDataBinderFactory b) {
        String name = req.getHeader("X-User-Name");
        String role = req.getHeader("X-User-Role");
        return new UserInfo(name, role);
    }
}

record UserInfo(String name, String role) {}

// 自定义拦截器：统计请求耗时
class TimingInterceptor implements HandlerInterceptor {
    private final ThreadLocal<Long> timer = new ThreadLocal<>();
    @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object h) {
        timer.set(System.currentTimeMillis()); return true; }
    @Override public void postHandle(HttpServletRequest req, HttpServletResponse resp, Object h,
            ModelAndView mv) {
        System.out.println("[Timing] " + req.getRequestURI() + " cost=" +
            (System.currentTimeMillis() - timer.get()) + "ms"); }
}

@Controller
class MeController {
    @GetMapping("/me")
    @ResponseBody String me(@CurrentUser UserInfo user) {
        return "user=" + user.name() + ",role=" + user.role();
    }
}

@Configuration
class MvcAdvancedConfig implements WebMvcConfigurer {
    @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> rs) {
        rs.add(new CurrentUserResolver()); }
    @Override public void addInterceptors(InterceptorRegistry r) {
        r.addInterceptor(new TimingInterceptor()); }
}

class MvcAdvancedTest {
    @Test
    void testCustomArgResolverAndInterceptor() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MeController())
            .setCustomArgumentResolvers(new CurrentUserResolver())
            .addInterceptors(new TimingInterceptor())
            .build();
        mvc.perform(get("/me")
                .header("X-User-Name", "admin")
                .header("X-User-Role", "super"))
           .andExpect(status().isOk())
           .andExpect(content().string("user=admin,role=super"));
    }
}
