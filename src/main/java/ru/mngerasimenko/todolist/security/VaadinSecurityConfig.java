package ru.mngerasimenko.todolist.security;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.view.LoginView;

import java.io.IOException;

@EnableWebSecurity
@Configuration
public class VaadinSecurityConfig extends VaadinWebSecurity {

    private final UserAuthService userAuthService;

    public VaadinSecurityConfig(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.addFilterBefore(autoLoginFilter(), UsernamePasswordAuthenticationFilter.class);

        http.authorizeHttpRequests(auth ->
                auth.requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/images/*.png"))
                        .permitAll()
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/VAADIN/**"))
                        .permitAll()
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/frontend/**"))
                        .permitAll().requestMatchers(AntPathRequestMatcher.antMatcher("/.well-known/**"))
                        .permitAll()
        );

        super.configure(http);
        setLoginView(http, LoginView.class);
    }

    private OncePerRequestFilter autoLoginFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain)
                    throws ServletException, IOException {
                HttpSessionSecurityContextRepository securityContextRepository =
                        new HttpSessionSecurityContextRepository();

                SecurityContext context = securityContextRepository.loadContext(
                        new HttpRequestResponseHolder(request, response)
                );

                Authentication existingAuth = context.getAuthentication();

                if (existingAuth == null || !existingAuth.isAuthenticated() ||
                        (existingAuth.getPrincipal() instanceof String &&
                                existingAuth.getPrincipal().equals("anonymousUser"))) {

                    UserDto authUser = userAuthService.getAuthUser(request);
                    if (authUser != null) {
                        UserDetails userDetails = userAuthService.getUserDetailsByName(authUser.getName());
                        Authentication auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                        context.setAuthentication(auth);
                        securityContextRepository.saveContext(
                                context,
                                request,
                                response
                        );
                        System.out.println("Auto-login performed for user: " + authUser.getName());
                        response.sendRedirect(determineRedirectUrl(request));

                        return;
                    }
                }

                filterChain.doFilter(request, response);
            }

            private String determineRedirectUrl(HttpServletRequest request) {
//                String requestURI = request.getRequestURI();
//                if (requestURI.equals("/") || requestURI.equals("/login")) {
//                    return request.getContextPath() + "/";
//                }
                return request.getContextPath() + "/";
            }

            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                String path = request.getRequestURI();
                return path.startsWith("/VAADIN/") ||
                        path.startsWith("/frontend/") ||
                        path.startsWith("/images/") ||
                        path.startsWith("/api/") ||
                        path.contains("/login");
            }

        };
    }
}