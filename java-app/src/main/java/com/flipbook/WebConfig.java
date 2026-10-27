package com.flipbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the converted page images at /books/{id}/page-N.jpg */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.storage-dir}")
    private String storageDir;

    @Value("${app.cors.allowed-origins:}")
    private String customCorsOrigins;

    private Path resolveStorageDir() {
        Path p = Paths.get(storageDir);
        if (!Files.exists(p) && Files.exists(Paths.get("java-app", storageDir))) {
            return Paths.get("java-app", storageDir).toAbsolutePath().normalize();
        }
        return p.toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = resolveStorageDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/books/**")
                .addResourceLocations(location);
    }

    @Override
    public void addCorsMappings(org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
        List<String> origins = new ArrayList<>(List.of("chrome-extension://*", "http://localhost:*", "http://127.0.0.1:*"));
        if (customCorsOrigins != null && !customCorsOrigins.isBlank()) {
            for (String origin : customCorsOrigins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty()) {
                    origins.add(trimmed);
                }
            }
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "OPTIONS", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}


