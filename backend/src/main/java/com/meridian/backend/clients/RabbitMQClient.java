package com.meridian.backend.clients;

import com.meridian.backend.core.config.AppConfig;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.stereotype.Component;

import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.KeyManagementException;

@Component
public class RabbitMQClient {

    private final ConnectionFactory connectionFactory;

    public RabbitMQClient(AppConfig config) {
        this.connectionFactory = new ConnectionFactory();
        try {
            this.connectionFactory.setUri(config.getRabbitmqUrl());
        } catch (URISyntaxException | NoSuchAlgorithmException | KeyManagementException ex) {
            throw new IllegalArgumentException("Invalid RabbitMQ URL", ex);
        }
        // Explicitly set vhost to ensure it's properly configured
        this.connectionFactory.setVirtualHost("/");
        connectionFactory.setConnectionTimeout(3000);
        connectionFactory.setRequestedHeartbeat(30);
    }

    public boolean ping() {
        try {
            Connection connection = connectionFactory.newConnection();
            try {
                boolean open = connection.isOpen();
                connection.close();
                return open;
            } catch (Exception ex) {
                try { connection.close(); } catch (Exception ignored) {}
                return false;
            }
        } catch (Exception ex) {
            return false;
        }
    }
}
