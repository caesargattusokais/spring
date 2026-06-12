package com.example.usage;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Controller
@RequestMapping("/users")
class UserController {
    @GetMapping("/{id}")
    @ResponseBody String get(@PathVariable Long id) {
        return "{\"id\":" + id + ",\"name\":\"zs\"}";
    }
    @PostMapping
    @ResponseBody String create(@RequestParam String name) {
        return "{\"result\":\"ok\",\"name\":\"" + name + "\"}";
    }
}

@Configuration
@EnableWebMvc
@ComponentScan
class MvcConfig implements WebMvcConfigurer {}

class MvcExampleTest {
    @Test
    void testController() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserController()).build();
        mvc.perform(get("/users/123")).andExpect(status().isOk())
           .andExpect(content().string("{\"id\":123,\"name\":\"zs\"}"));
        mvc.perform(post("/users").param("name", "ls")).andExpect(status().isOk())
           .andExpect(content().string("{\"result\":\"ok\",\"name\":\"ls\"}"));
    }
}
