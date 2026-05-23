package org.javaup.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Value("${localmind.image-upload-dir:./data/uploads/imgs/}")
    private String imageUploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadRoot = Paths.get(imageUploadDir).toAbsolutePath().normalize();
        registry.addResourceHandler("/imgs/**")
                .addResourceLocations(uploadRoot.toUri().toString(), "classpath:/static/imgs/");
    }
}
