package com.example.aiinbox.di

import com.example.aiinbox.llm.FakeLlmEngine
import com.example.aiinbox.llm.LlmEngine
import com.example.aiinbox.ocr.FakeOcrEngine
import com.example.aiinbox.ocr.OcrEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [LlmBindsModule::class])
abstract class TestLlmModule {
    @Binds @Singleton
    abstract fun bindLlmEngine(impl: FakeLlmEngine): LlmEngine
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [OcrModule::class])
object TestOcrModule {
    @Provides
    @Singleton
    fun provideOcrEngine(): OcrEngine = FakeOcrEngine()
}
