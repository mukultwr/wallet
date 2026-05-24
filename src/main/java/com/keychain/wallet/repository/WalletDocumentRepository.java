package com.keychain.wallet.repository;

import com.keychain.wallet.model.mongo.WalletDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WalletDocumentRepository extends MongoRepository<WalletDocument, String> {
}
