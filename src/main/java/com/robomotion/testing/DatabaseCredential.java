package com.robomotion.testing;

/**
 * DatabaseCredential holds database connection parameters for testing.
 */
public class DatabaseCredential {
    private String server;
    private int port;
    private String database;
    private String username;
    private String password;

    public DatabaseCredential() {
    }

    public DatabaseCredential(String server, int port, String database, String username, String password) {
        this.server = server;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    public String getServer() {
        return server;
    }

    public DatabaseCredential setServer(String server) {
        this.server = server;
        return this;
    }

    public int getPort() {
        return port;
    }

    public DatabaseCredential setPort(int port) {
        this.port = port;
        return this;
    }

    public String getDatabase() {
        return database;
    }

    public DatabaseCredential setDatabase(String database) {
        this.database = database;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public DatabaseCredential setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getPassword() {
        return password;
    }

    public DatabaseCredential setPassword(String password) {
        this.password = password;
        return this;
    }
}
