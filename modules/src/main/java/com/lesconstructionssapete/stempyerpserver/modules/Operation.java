package com.lesconstructionssapete.stempyerpserver.modules;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

public interface Operation<I, O> {
  O execute(UnitOfWork uow, I input);
}
