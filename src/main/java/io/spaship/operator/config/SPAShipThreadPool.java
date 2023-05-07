package io.spaship.operator.config;


import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class SPAShipThreadPool {

    private SPAShipThreadPool(){}
    private static final String THREAD_NAME_PREFIX = "spaship-";


    public static ExecutorService cachedThreadPool() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, THREAD_NAME_PREFIX + UUID.randomUUID().toString().split("-")[0]);
            t.setDaemon(false);
            return t;
        });
    }

}
