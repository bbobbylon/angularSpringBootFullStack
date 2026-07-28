package com.bob.angularspringbootfullstack.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;


/**
 * MVC resource handler configuration.
 * <p>
 * Maps the {@code /user/profile/image/**} URL path to the filesystem directory
 * where profile images are stored. The directory is resolved from the
 * {@code app.image.storage-path} property (env {@code IMAGE_STORAGE_PATH}), so it
 * is portable across local dev, Docker, and cloud — no hardcoded developer path.
 * This lets the browser load images via a plain {@code <img>} src without hitting
 * a controller.
 * <p>
 * The path is also listed in {@code SecurityConfig.PUBLIC_URLS} so Spring Security
 * permits unauthenticated GET requests to it.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** Filesystem directory holding profile images; see {@code app.image.storage-path}. */
    @Value("${app.image.storage-path}")
    private String imageStoragePath;

    /**
     * Registers a resource handler that serves profile images ({@code {email}.png})
     * from {@link #imageStoragePath} at {@code /user/profile/image/{email}.png}.
     * The location is normalised to an absolute {@code file:} URI with a trailing
     * slash so Spring resolves child resources correctly on every OS.
     *
     * @param registry the Spring MVC resource handler registry
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Normalise to forward slashes and force a trailing slash so Spring resolves
        // child resources correctly (Path.toUri() can omit it for a not-yet-created dir).
        String base = Paths.get(imageStoragePath).toAbsolutePath().normalize().toString().replace('\\', '/');
        registry.addResourceHandler("/user/profile/image/**")
                .addResourceLocations("file:" + base + "/");
    }

    /**
     * Forwards any GET path with no file extension and no {@code /api}-style prefix to
     * {@code index.html}, so Angular's client-side router can render the right page on a
     * hard refresh or a bookmarked deep link (e.g. {@code /dashboard}). Only relevant when
     * Angular is compiled into this jar's static resources (Docker/prod) — in local dev,
     * Angular's own dev server (port 4200) handles this itself and never reaches here.
     * Real REST controllers still win: Spring MVC matches {@code @RequestMapping} handlers
     * before this lower-priority view-controller mapping, so no API route is shadowed.
     *
     * @param registry the Spring MVC view controller registry
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/{path:[^.]*}").setViewName("forward:/index.html");
        registry.addViewController("/**/{path:[^.]*}").setViewName("forward:/index.html");
    }
}

