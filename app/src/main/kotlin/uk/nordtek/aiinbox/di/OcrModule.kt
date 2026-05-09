package uk.nordtek.aiinbox.di

import uk.nordtek.aiinbox.ocr.MlKitOcrEngine
import uk.nordtek.aiinbox.ocr.OcrEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {
    @Binds
    @Singleton
    abstract fun bindOcrEngine(impl: MlKitOcrEngine): OcrEngine
}
