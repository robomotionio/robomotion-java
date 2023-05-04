package com.robomotion.app;

public class RuntimeNotInitializedException extends Exception {
    public RuntimeNotInitializedException() {
        super("Runtime was not initialized");
    }
}
