package com.example.uvctestcamera.engine;

public abstract class Component {

    static {
        try {
            System.loadLibrary("engine");
            libraryFound = true;
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
            libraryFound = false;
        }
    }

    protected Component() {
        // Base constructor; subclasses typically call createInstance() in their own ctor.
    }

    public abstract long createInstance();

    public abstract void destroy();

    public static boolean libraryFound = false;
    public static final String tag = "Component";
}
