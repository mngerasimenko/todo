package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.FeatureFlagOverride;

/** Долговечные runtime-override'ы feature-флагов. Читается при старте, пишется на каждый
 *  {@code PUT/DELETE /api/admin/flags} — то есть считанные разы за жизнь сервиса. */
@Repository
public interface FeatureFlagOverrideRepository extends JpaRepository<FeatureFlagOverride, String> {
}
