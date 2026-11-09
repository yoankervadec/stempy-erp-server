package com.lesconstructionssapete.stempyerpserver.app;

import java.sql.SQLException;

import com.lesconstructionssapete.stempyerpserver.app.config.ApplicationEnvironment;
import com.lesconstructionssapete.stempyerpserver.app.config.ApplicationProperties;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataSources;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseConfig;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.ErrorTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.web.ExceptionMapper;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthChecks;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthRoutes;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;
import com.zaxxer.hikari.HikariDataSource;

public class Main {
  public static void main(String[] args) {

    HikariDataSource dataSource;

    var db = DatabaseConfig.of(
        ApplicationEnvironment.get("DATABASE_HOST"),
        ApplicationEnvironment.getInt("DATABASE_PORT"),
        ApplicationEnvironment.get("DATABASE_NAME"),
        ApplicationProperties.getByPrefix("database.url.params"),
        ApplicationEnvironment.get("DATABASE_USER"),
        ApplicationEnvironment.get("DATABASE_PASSWORD"));

    dataSource = DataSources.create(db);

    var uowFactory = new UnitOfWorkFactory(dataSource);

    // try ( dataSource = DataSources.create(db)) {
    // UnitOfWorkFactory uowFactory = new UnitOfWorkFactory(dataSource);
    // var result = uowFactory.execute(uow -> postingServices.post(uow, "INV1234",
    // requestContext));
    // }

    var exceptionMapper = new ExceptionMapper(new ErrorTranslator());
    var webServer = new WebServer(WebServerConfig.ofPort(8081), exceptionMapper);

    var health = new HealthChecks().add("database", () -> uowFactory.executeReadOnly(uow -> {
      try (var st = uow.connection().createStatement(); var rs = st.executeQuery("SELECT 1")) {
        return rs.next();
      } catch (SQLException e) {
        throw new DataAccessException(e);
      }
    }));

    webServer.start(routes -> {
      HealthRoutes.register(routes, health);
    });
    Runtime.getRuntime().addShutdownHook(new Thread(webServer::stop));

  }
}
