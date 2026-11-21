package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.web.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enforces {@link OpsRequires}. It answers with RFC 7807 and stops the chain
 * rather than throwing, so a refusal looks the same as one from the session
 * filter. A denial is audited with the permission and the route.
 */
public class OpsPermissionInterceptor implements HandlerInterceptor {

    private final ProblemWriter problems;
    private final AuditTrail audit;

    public OpsPermissionInterceptor(ProblemWriter problems, AuditTrail audit) {
        this.problems = problems;
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws java.io.IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        OpsRequires requirement = AnnotatedElementUtils.findMergedAnnotation(
                method.getMethod(), OpsRequires.class);
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    method.getBeanType(), OpsRequires.class);
        }
        if (requirement == null) {
            return true;
        }
        OpsContext context = (OpsContext) request.getAttribute(OpsContext.ATTRIBUTE);
        if (context == null) {
            problems.write(response, HttpStatus.UNAUTHORIZED, "Unauthorised",
                    "an operator session is required", Map.of("code", "OPS_SESSION_REQUIRED"));
            return false;
        }
        if (context.allows(requirement.value())) {
            return true;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("permission", requirement.value().name());
        detail.put("method", request.getMethod());
        detail.put("path", request.getRequestURI());
        audit.recordOperator(context.tenantId(), context.user().userId(), "OPS_FORBIDDEN",
                AuditTrail.Outcome.DENIED, detail);

        problems.write(response, HttpStatus.FORBIDDEN, "Forbidden",
                "this role does not carry the required permission",
                Map.of("code", "PERMISSION_DENIED", "permission", requirement.value().name(),
                        "role", context.role().name()));
        return false;
    }
}
