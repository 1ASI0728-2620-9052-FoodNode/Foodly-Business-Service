package com.foodly.business.infrastructure.persistence;
import com.foodly.business.domain.model.*;
import com.foodly.business.domain.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import java.util.*;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Repository
public class BusinessRepository implements IHuariqueRepository, IMenuRepository {
    private final MongoTemplate mongo;
    public BusinessRepository(MongoTemplate mongo) { this.mongo = mongo; }
    public Huarique save(Huarique h) { return mongo.save(HuariqueDocument.from(h)).toDomain(); }
    public Menu save(Menu m) { return mongo.save(MenuDocument.from(m)).toDomain(); }
    public Optional<Huarique> findById(UUID id) { return Optional.ofNullable(mongo.findById(id, HuariqueDocument.class)).map(HuariqueDocument::toDomain); }
    public Optional<Huarique> findByOwnerId(String ownerId) { return Optional.ofNullable(mongo.findOne(Query.query(where("ownerId").is(ownerId)), HuariqueDocument.class)).map(HuariqueDocument::toDomain); }
    public Optional<Menu> findByHuariqueId(UUID id) { return Optional.ofNullable(mongo.findOne(Query.query(where("huariqueId").is(id)), MenuDocument.class)).map(MenuDocument::toDomain); }
    public List<Huarique> findAll(int page, int size) {
        return mongo.find(new Query().with(PageRequest.of(page, size, Sort.by("id"))), HuariqueDocument.class).stream().map(HuariqueDocument::toDomain).toList();
    }
}
