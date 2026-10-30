package com.helpdesk.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protects /internal/** (the API used by ai-service, not by people). Callers must send the shared
 * service key in X-Api-Key. Fails closed: a blank configured key rejects every call.
 * Registered for /internal/* only (see InternalApiConfig), so the public /api is unaffected.
 */
public class ServiceKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    private static final Logger log = LoggerFactory.getLogger(ServiceKeyFilter.class);

    private final byte[] expected;

    public ServiceKeyFilter(String expectedKey) {
        this.expected = expectedKey == null ? new byte[0] : expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        boolean ok = expected.length > 0 && provided != null
                // constant-time comparison: doesn't leak how many characters matched
                && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
        if (ok) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Rejected internal API call to {} from {} ({} key)", request.getRequestURI(),
                request.getRemoteAddr(), provided == null ? "missing" : "wrong");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"Unauthorized","status":401,\
                "detail":"Missing or invalid X-Api-Key for the internal API","code":"INVALID_SERVICE_KEY"}""");
    }
}
