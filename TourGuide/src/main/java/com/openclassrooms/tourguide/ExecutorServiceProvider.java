package com.openclassrooms.tourguide;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ExecutorServiceProvider {
    public static final ExecutorService executor = Executors.newFixedThreadPool(2000);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down ExecutorService...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));
    }

    private ExecutorServiceProvider() {}

    public static ExecutorService getExecutor() {
        return executor;
    }
}
