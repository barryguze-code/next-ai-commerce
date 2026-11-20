package com.nextaicommerce.platform.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Keep production SQL intact while resolving explicit schema references inside a disposable fixture. */
public final class FixtureMigrations {
    private FixtureMigrations() {}
    public static String location(String schema) {
        if ("public".equals(schema)) return "classpath:db/migration";
        if (!schema.matches("[a-z][a-z0-9_]*_[a-f0-9]{32}"))
            throw new IllegalArgumentException("Expected a unique fixture schema");
        return Named.location;
    }

    private static final class Named {
        private static final String location = prepare();
        private static String prepare() {
            Path source = Path.of("src/main/resources/db/migration");
            try (var migrations = Files.list(source)) {
                Path directory = Files.createTempDirectory("nextai-test-migrations-");
                directory.toFile().deleteOnExit();
                for (var migration : migrations.filter(p -> p.toString().endsWith(".sql")).toList()) {
                    Path target = directory.resolve(migration.getFileName());
                    if (migration.getFileName().toString().startsWith("V115__")) {
                        String sql = Files.readString(migration)
                                .replace("pg_catalog,public AS", "pg_catalog,${flyway:defaultSchema} AS")
                                .replace("public.app_users", "${flyway:defaultSchema}.app_users")
                                .replace("public.platform_administrators", "${flyway:defaultSchema}.platform_administrators");
                        Files.writeString(target, sql);
                    } else {
                        Files.copy(migration, target);
                    }
                    target.toFile().deleteOnExit();
                }
                return "filesystem:" + directory;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
