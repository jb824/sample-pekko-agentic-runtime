package com.example.agent.rag.service;

import com.example.agent.rag.RagRetrieveRequest;
import com.example.agent.rag.RagRetrieveResponse;

public interface TenantVectorStore {
    void upsertProfile(String collection, ProfileDocument profile);
    RagRetrieveResponse retrieve(RagRetrieveRequest request);
}
