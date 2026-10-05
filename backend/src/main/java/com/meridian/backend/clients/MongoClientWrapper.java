package com.meridian.backend.clients;

import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.springframework.stereotype.Component;

@Component
public class MongoClientWrapper {

    private final MongoClient mongoClient;

    public MongoClientWrapper(MongoClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    public boolean ping() {
        try {
            mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public com.mongodb.client.MongoDatabase getDatabase(String name) {
        return mongoClient.getDatabase(name);
    }

    public void close() {
        // Do not close the shared MongoClient - let Spring manage its lifecycle
    }
}