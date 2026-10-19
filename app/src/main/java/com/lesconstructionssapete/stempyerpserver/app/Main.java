package com.lesconstructionssapete.stempyerpserver.app;

import com.lesconstructionssapete.stempyerpserver.app.config.ApplicationEnvironment;
import com.lesconstructionssapete.stempyerpserver.app.config.ApplicationProperties;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataSources;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseConfig;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.zaxxer.hikari.HikariDataSource;

public class Main {
  public static void main(String[] args) {

    var db = DatabaseConfig.of(
        ApplicationEnvironment.get("DATABASE_HOST"),
        ApplicationEnvironment.getInt("DATABASE_PORT"),
        ApplicationEnvironment.get("DATABASE_NAME"),
        ApplicationProperties.getByPrefix("database.url.params"),
        ApplicationEnvironment.get("DATABASE_USER"),
        ApplicationEnvironment.get("DATABASE_PASSWORD"));

    try (HikariDataSource dataSource = DataSources.create(db)) {
      UnitOfWorkFactory uowFactory = new UnitOfWorkFactory(dataSource);
      // var result = uowFactory.execute(uow -> postingServices.post(uow, "INV1234",
      // requestContext));
    }
  }
}
