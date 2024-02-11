package ru.mngerasimenko.shoppinglist.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.shoppinglist.entity.Shopping;

public interface ShoppingRepository extends JpaRepository<Shopping, Long> {

    //@Query("SELECT s FROM Shopping s WHERE s.user.id=:userId ORDER BY s.dateTime DESC")
    //List<Shopping> findAll(@Param("userId") long userId);

    List<Shopping> findAllByUserIdOrderByDateTime(long userid);

    Shopping findById(long id);

    @Modifying
    @Transactional
    void deleteByUserIdAndId(long userId, long shoppingId);
}
