package com.example.aiinbox.di

import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.FakeLlmEngine
import com.example.aiinbox.llm.LlmEngine
import com.example.aiinbox.llm.LlmResponseParser
import com.example.aiinbox.llm.PromptBuilder
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
    /** Plan 1: LlmEngine の本番束ね先は FakeLlmEngine。Plan 2 で MediaPipeLlmEngine に切替。 */
    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: FakeLlmEngine): LlmEngine
}

@Module
@InstallIn(SingletonComponent::class)
object LlmProvidersModule {

    @Provides
    @Singleton
    fun providePromptBuilder(): PromptBuilder = PromptBuilder()

    @Provides
    @Singleton
    fun provideContentHintDetector(): ContentHintDetector = ContentHintDetector()

    @Provides
    @Singleton
    fun provideLlmResponseParser(): LlmResponseParser = LlmResponseParser(ZoneId.systemDefault())
}
