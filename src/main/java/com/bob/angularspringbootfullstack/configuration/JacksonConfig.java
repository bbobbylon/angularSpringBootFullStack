package com.bob.angularspringbootfullstack.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Declares the application's primary {@link ObjectMapper} bean.
 *
 * <p>This bean is used by servlet filters and security handlers (e.g. {@code RateLimitFilter},
 * the 401/403 entry points) that need to write JSON responses outside of Spring MVC's normal
 * {@code HttpMessageConverter} pipeline — i.e. before or after the DispatcherServlet has had
 * a chance to run.
 *
 * <p><b>Why not {@code @ConditionalOnMissingBean}?</b> That annotation is designed for
 * {@code @AutoConfiguration} classes (processed after all user configs). In a regular
 * {@code @Configuration} the ordering is unpredictable: if Jackson's auto-configuration has
 * already registered its own bean, this class's condition evaluates to {@code false} and the
 * bean is silently suppressed — leaving downstream filters with no candidate. Declaring the bean
 * unconditionally + {@code @Primary} is the correct pattern here: Spring Boot's
 * {@code JacksonAutoConfiguration} uses its own {@code @ConditionalOnMissingBean} and will
 * defer to this bean automatically.
 */
@Configuration
public class JacksonConfig {

    /**
     * Primary shared ObjectMapper, configured with Java 8 date/time support so that
     * {@link java.time.LocalDateTime} fields in {@code HttpResponse} serialize as ISO-8601
     * strings rather than arrays.
     *
     * @return the configured {@link ObjectMapper}
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
