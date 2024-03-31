package ru.mngerasimenko.todolist.security;

import com.vaadin.flow.spring.security.VaadinWebSecurity;


//@EnableWebSecurity
//@Configuration
public class SecurityConfig extends VaadinWebSecurity {
//    private final UserService userService;
//
//    public SecurityConfig(UserService userService) {
//        this.userService = userService;
//    }
//
//    @Override
//    protected void configure(HttpSecurity http) throws Exception {
//        http.authorizeHttpRequests(auth ->
//                auth.requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.GET, "images/*.png"))
//                        .permitAll());
//        super.configure(http);
//        setLoginView(http, LoginView.class);
//    }
//
//    public UserDetailsService users() {
//        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
//        List<User> userList = userService.getAll();
//        for (User user : userList) {
//            UserDetails userDetails = org.springframework.security.core.userdetails.User
//                    .builder()
//                    .username(user.getName())
//                    .password(user.getPassword())
//                    .roles("USER", "ADMIN")
//                    .build();
//            manager.createUser(userDetails);
//        }
//        return manager;
//    }


}
