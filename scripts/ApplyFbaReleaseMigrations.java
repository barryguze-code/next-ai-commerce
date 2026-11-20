import java.nio.file.*;import java.util.*;import org.flywaydb.core.Flyway;
/** Separate, explicit release actor. Never turns runtime Flyway or integration workers on. */
public class ApplyFbaReleaseMigrations {
 public static void main(String[] args)throws Exception{
  String url=System.getenv("RELEASE_DB_URL"),user=System.getenv("RELEASE_DB_USER"),password=System.getenv("RELEASE_DB_PASSWORD");
  if(url==null||!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):5432/next_ai_commerce")||!"nextcommerce".equals(user)||password==null)throw new IllegalStateException("Unexpected migration target");
  var flyway=Flyway.configure().dataSource(url,user,password).locations("filesystem:"+Path.of(args[0]).toAbsolutePath()).createSchemas(false).target("146").initSql("SET lock_timeout='5s'; SET statement_timeout='120s'").load();
  flyway.validate();var pending=Arrays.stream(flyway.info().pending()).map(m->m.getVersion().toString()).toList();if(!pending.equals(List.of("145","146")))throw new IllegalStateException("Unexpected pending migration range");
  if(args[1].equals("validate")){System.out.println("Validated exact pending range145–146");return;}
  if(!args[1].equals("migrate"))throw new IllegalArgumentException("Unknown release operation");
  var result=flyway.migrate();if(result.migrationsExecuted!=2)throw new IllegalStateException("Expected exactly two migrations");System.out.println("Applied reviewed145–146");
 }
}
