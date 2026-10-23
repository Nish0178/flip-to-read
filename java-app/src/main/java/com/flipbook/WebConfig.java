package com.flipbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the converted page images at /books/{id}/page-N.jpg */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.storage-dir}")
    private String storageDir;

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
}

