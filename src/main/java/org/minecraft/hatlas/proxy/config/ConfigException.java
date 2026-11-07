package org.minecraft.hatlas.proxy.config;

/** The config file could not be read or holds invalid values; the message names every problem. */
public final class ConfigException extends Exception {

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
