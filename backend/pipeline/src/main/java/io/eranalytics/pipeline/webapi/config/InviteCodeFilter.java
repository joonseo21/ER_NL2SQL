package io.eranalytics.pipeline.webapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.webapi.service.InviteService;
import io.eranalytics.pipeline.webapi.service.WebApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class InviteCodeFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "webapi.verifiedInviteCode";
    private final InviteService invites;
    private final ObjectMapper json;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/")
                || ("POST".equals(request.getMethod()) && "/api/invite/verify".equals(path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String code;
        try {
            List<String> headers = Collections.list(request.getHeaders("X-Invite-Code"));
            if (headers.size() != 1) throw WebApiException.invalidInvite();
            code = invites.requireActive(headers.getFirst());
        } catch (WebApiException error) {
            response.setStatus(error.getStatus().value());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            json.writeValue(
                    response.getWriter(),
                    Map.of("code", error.getCode(), "message", error.getMessage()));
            return;
        }
        request.setAttribute(ATTRIBUTE, code);
        chain.doFilter(request, response);
    }
}
