package com.example.instantvoicetranslate.di

import com.example.instantvoicetranslate.translation.TextTranslator
import com.example.instantvoicetranslate.translation.FreeTranslator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindTextTranslator(impl: FreeTranslator): TextTranslator
}
