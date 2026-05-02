package com.example.aiinbox.di

import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.LlmEngine
import com.example.aiinbox.llm.LlmResponseParser
import com.example.aiinbox.llm.MediaPipeLlmEngine
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
    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: MediaPipeLlmEngine): LlmEngine
}

@Module
@InstallIn(SingletonComponent::class)
object LlmProvidersModule {
    @Provides @Singleton fun providePromptBuilder(): PromptBuilder = PromptBuilder()
    @Provides @Singleton fun provideContentHintDetector(): ContentHintDetector = ContentHintDetector()
    @Provides @Singleton fun provideLlmResponseParser(): LlmResponseParser =
        LlmResponseParser(ZoneId.systemDefault())
}
