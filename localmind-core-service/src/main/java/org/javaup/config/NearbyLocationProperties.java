package org.javaup.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "localmind.nearby")
public class NearbyLocationProperties {

    /**
     * true: use the real coordinates submitted by the frontend.
     * false: force the configured mock coordinates for local development.
     */
    private Boolean realCoordinateEnabled = false;

    private Double mockX = 120.149993;

    private Double mockY = 30.334229;
}
