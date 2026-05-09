package uk.nordtek.aiinbox.di

import uk.nordtek.aiinbox.llm.ContentHintDetector
import uk.nordtek.aiinbox.llm.LlmEngine
import uk.nordtek.aiinbox.llm.LlmResponseParser
import uk.nordtek.aiinbox.llm.LiteRtLmEngine
import uk.nordtek.aiinbox.llm.PromptBuilder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmBindsModule {
    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: LiteRtLmEngine): LlmEngine
}

@Module
@InstallIn(SingletonComponent::class)
object LlmProvidersModule {
    @Provides @Singleton fun providePromptBuilder(): PromptBuilder = PromptBuilder()
    @Provides @Singleton fun provideContentHintDetector(): ContentHintDetector = ContentHintDetector()
    @Provides @Singleton fun provideLlmResponseParser(): LlmResponseParser =
        LlmResponseParser(ZoneId.systemDefault())
}
