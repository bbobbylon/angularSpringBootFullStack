/*
package com.bob.angularspringbootfullstack.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

*/
/**
 * MVC resource handler configuration.
 * <p>
 * Maps the {@code /user/profile/image/**} URL path to the local filesystem
 * directory where profile images are stored ({@code ~/Downloads/images/}).
 * This allows the browser to load profile images directly via an
 * {@code <img>} src without going through a controller endpoint.
 * <p>
 * The path is also listed in {@link SecurityConfig#PUBLIC_URLS} so Spring
 * Security permits unauthenticated GET requests to it.
 *
 * <p>-----------------------------------------------------------------------
 * TODO(dev-only): This resource handler is a local development workaround.
 * It maps a hardcoded path on the developer's machine (~/Downloads/images/)
 * and will not work in Docker or any CI/CD environment.
 * Replace with a proper {@code GET /user/profile/image/{email}} controller
 * endpoint that reads from a configurable path (e.g. an environment variable
 * or application.properties value), or migrate image storage to a cloud
 * provider such as AWS S3 so the backend is not responsible for serving
 * files at all.
 * -----------------------------------------------------------------------
 *//*

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    */
/**
 * Registers a resource handler that serves profile images from the local
 * filesystem. Files are stored as {@code {email}.png} under
 * {@code ~/Downloads/images/} and are accessible at
 * {@code /user/profile/image/{email}.png}.
 *
 * @param registry the Spring MVC resource handler registry
 *//*

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/user/profile/image/**")
                .addResourceLocations("file:" + System.getProperty("user.home") + "/Downloads/images/");
    }
}
*/
