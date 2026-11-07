package org.minecraft.hatlas.proxy.db;

/** The database could not be opened or prepared; the plugin then stays loaded but inactive. */
public final class DatabaseException extends Exception {

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
