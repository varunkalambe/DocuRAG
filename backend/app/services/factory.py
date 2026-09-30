from app.chunking.semantic import SemanticChunker
from app.embeddings.huggingface import HuggingFaceEmbeddingAdapter
from app.generation.groq import GroqAdapter
from app.indexing.chroma_indexer import ChromaIndexer
from app.query.context import ContextAssembler
from app.query.prompt import GroundedPromptBuilder
from app.query.relevance import RelevanceFilter
from app.query.retrieval import ChromaRetriever
from app.query.validation import QueryValidator
from app.services.ingestion import IngestionService
from app.services.query import QueryService
from app.services.rag import RagOrchestrator
from app.vector_store.chroma import get_vector_store


_ingestion_service: IngestionService | None = None
_query_service: QueryService | None = None
_rag_orchestrator: RagOrchestrator | None = None


def get_ingestion_service() -> IngestionService:
    global _ingestion_service

    if _ingestion_service is None:
        store = get_vector_store()
        _ingestion_service = IngestionService(
            chunker=SemanticChunker(),
            embedder=HuggingFaceEmbeddingAdapter(),
            indexer=ChromaIndexer(store),
        )

    return _ingestion_service


def get_query_service() -> QueryService:
    global _query_service

    if _query_service is None:
        store = get_vector_store()
        _query_service = QueryService(
            validator=QueryValidator(store),
            embedder=HuggingFaceEmbeddingAdapter(),
            retriever=ChromaRetriever(store),
            relevance_filter=RelevanceFilter(),
            context_assembler=ContextAssembler(),
            prompt_builder=GroundedPromptBuilder(),
            generator=GroqAdapter(),
        )

    return _query_service


def get_rag_orchestrator() -> RagOrchestrator:
    global _rag_orchestrator

    if _rag_orchestrator is None:
        _rag_orchestrator = RagOrchestrator(
            ingestion_service=get_ingestion_service(),
            query_service=get_query_service(),
        )

    return _rag_orchestrator
