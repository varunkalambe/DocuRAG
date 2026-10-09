package com.pdfrag.api;

import com.pdfrag.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Outermost filter so every response, including errors, carries CORS headers. Credential-free:
 * allowed origins are an explicit list plus an optional full-match regex (e.g. Vercel previews).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter extends OncePerRequestFilter {

    private static final String ALLOWED_METHODS = "GET, POST, DELETE, OPTIONS";

    private final List<String> origins;
    private final Pattern originRegex;

    public CorsFilter(AppProperties props) {
        this.origins = props.allowedOrigins();
        String regex = props.corsAllowOriginRegex();
        this.originRegex = (regex == null || regex.isBlank()) ? null : Pattern.compile(regex);
    }

    private boolean isAllowed(String origin) {
        return origins.contains(origin) || (originRegex != null && originRegex.matcher(origin).matches());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String origin = request.getHeader("Origin");
        if (origin == null) {
            chain.doFilter(request, response);
            return;
        }

        boolean allowed = isAllowed(origin);
        response.addHeader("Vary", "Origin");

        boolean preflight = "OPTIONS".equalsIgnoreCase(request.getMethod())
                && request.getHeader("Access-Control-Request-Method") != null;

        if (preflight) {
            if (!allowed) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Disallowed CORS origin");
                return;
            }
            String requested = request.getHeader("Access-Control-Request-Headers");
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
            response.setHeader("Access-Control-Allow-Headers", requested != null ? requested : "*");
            response.setHeader("Access-Control-Max-Age", "600");
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        if (allowed) {
            response.setHeader("Access-Control-Allow-Origin", origin);
        }
        chain.doFilter(request, response);
    }
}
