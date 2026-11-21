package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.web.ProblemWriter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class OpsWebConfig implements WebMvcConfigurer {

    private final ProblemWriter problems;
    private final AuditTrail audit;

    public OpsWebConfig(ProblemWriter problems, AuditTrail audit) {
        this.problems = problems;
        this.audit = audit;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new OpsCurrentSessionArgumentResolver());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OpsPermissionInterceptor(problems, audit))
                .addPathPatterns("/v1/ops/**");
    }
}
