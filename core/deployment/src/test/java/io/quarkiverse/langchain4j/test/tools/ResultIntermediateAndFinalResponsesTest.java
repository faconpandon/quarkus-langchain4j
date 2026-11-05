package io.quarkiverse.langchain4j.test.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkus.test.QuarkusUnitTest;

/**
 * {@link Result#intermediateResponses()} and {@link Result#finalResponse()} have existed upstream since
 * langchain4j 1.2.0 and are populated by {@code DefaultAiServices} for every {@code Result}-returning
 * method. They must be populated by the Quarkus implementation too.
 */
public class ResultIntermediateAndFinalResponsesTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(MyAiService.class, MyTools.class, MyChatModelSupplier.class, MyChatModel.class));

    @Inject
    MyAiService aiService;

    @Test
    @ActivateRequestContext
    void twoToolCallingRoundsAreReportedAsIntermediateResponses() {
        Result<String> result = aiService.chat("tools");

        assertThat(result.content()).isEqualTo("all done");

        // one intermediate response per round that asked for a tool, in order
        assertThat(result.intermediateResponses()).hasSize(2);
        assertThat(result.intermediateResponses().get(0).metadata().id()).isEqualTo("round-1");
        assertThat(result.intermediateResponses().get(1).metadata().id()).isEqualTo("round-2");
        assertThat(result.intermediateResponses())
                .allSatisfy(response -> assertThat(response.aiMessage().hasToolExecutionRequests()).isTrue());

        assertThat(result.toolExecutions()).hasSize(2);
    }

    @Test
    @ActivateRequestContext
    void finalResponseIsTheLastResponseFromTheModel() {
        Result<String> result = aiService.chat("tools");

        assertThat(result.finalResponse()).isNotNull();
        assertThat(result.finalResponse().metadata().id()).isEqualTo("final");
        assertThat(result.finalResponse().metadata().modelName()).isEqualTo("stub-model");
        assertThat(result.finalResponse().aiMessage().text()).isEqualTo("all done");
        assertThat(result.finalResponse().aiMessage().hasToolExecutionRequests()).isFalse();
        // the final response is not also listed as an intermediate one
        assertThat(result.intermediateResponses()).doesNotContain(result.finalResponse());
    }

    @Test
    @ActivateRequestContext
    void finalResponseIsPopulatedWhenNoToolIsCalled() {
        Result<String> result = aiService.chat("no-tools");

        assertThat(result.content()).isEqualTo("straight answer");
        assertThat(result.intermediateResponses()).isEmpty();
        assertThat(result.finalResponse()).isNotNull();
        assertThat(result.finalResponse().metadata().id()).isEqualTo("only");
        assertThat(result.finalResponse().aiMessage().text()).isEqualTo("straight answer");
    }

    @RegisterAiService(chatLanguageModelSupplier = MyChatModelSupplier.class, tools = MyTools.class)
    public interface MyAiService {
        Result<String> chat(@UserMessage String message);
    }

    @ApplicationScoped
    public static class MyTools {

        @Tool("first tool")
        public String first() {
            return "first-result";
        }

        @Tool("second tool")
        public String second() {
            return "second-result";
        }
    }

    public static class MyChatModelSupplier implements Supplier<ChatModel> {
        @Override
        public ChatModel get() {
            return new MyChatModel();
        }
    }

    public static class MyChatModel implements ChatModel {

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            List<ChatMessage> messages = chatRequest.messages();
            if (messages.size() == 1) {
                if ("no-tools".equals(((dev.langchain4j.data.message.UserMessage) messages.get(0)).singleText())) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("straight answer"))
                            .id("only")
                            .modelName("stub-model")
                            .tokenUsage(new TokenUsage(1, 1))
                            .finishReason(FinishReason.STOP)
                            .build();
                }
                return toolRequest("round-1", "first");
            }
            if (messages.size() == 3) {
                return toolRequest("round-2", "second");
            }
            if (messages.size() == 5) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("all done"))
                        .id("final")
                        .modelName("stub-model")
                        .tokenUsage(new TokenUsage(3, 4))
                        .finishReason(FinishReason.STOP)
                        .build();
            }
            throw new RuntimeException("Unexpected number of messages: " + messages.size());
        }

        private static ChatResponse toolRequest(String id, String toolName) {
            return ChatResponse.builder()
                    .aiMessage(new AiMessage("", List.of(
                            ToolExecutionRequest.builder()
                                    .id("call-" + toolName)
                                    .name(toolName)
                                    .arguments("{}")
                                    .build())))
                    .id(id)
                    .modelName("stub-model")
                    .tokenUsage(new TokenUsage(1, 2))
                    .finishReason(FinishReason.TOOL_EXECUTION)
                    .build();
        }
    }
}
