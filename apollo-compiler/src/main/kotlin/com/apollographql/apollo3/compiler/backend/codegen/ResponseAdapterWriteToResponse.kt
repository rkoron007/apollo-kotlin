package com.apollographql.apollo3.compiler.backend.codegen

import com.apollographql.apollo3.api.json.JsonWriter
import com.apollographql.apollo3.compiler.applyIf
import com.apollographql.apollo3.compiler.backend.ast.CodeGenerationAst
import com.apollographql.apollo3.compiler.escapeKotlinReservedWord
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode

internal fun CodeGenerationAst.ObjectType.writeToResponseFunSpec(generateFragmentsAsInterfaces: Boolean): FunSpec {
  return when (this.kind) {
    is CodeGenerationAst.ObjectType.Kind.ObjectWithFragments -> {
      if (generateFragmentsAsInterfaces) writeFragmentAsInterfacesToResponseFunSpec() else writeFragmentAsClassesToResponseFunSpec()
    }
    is CodeGenerationAst.ObjectType.Kind.FragmentDelegate -> writeFragmentDelegateToResponseFunSpec()
    else -> writeObjectToResponseFunSpec()
  }
}

private fun CodeGenerationAst.ObjectType.writeObjectToResponseFunSpec(): FunSpec {
  return FunSpec.builder("toResponse")
      .applyIf(!isTypeCase) { addModifiers(KModifier.OVERRIDE) }
      .addParameter(ParameterSpec(name = "writer", type = JsonWriter::class.asTypeName()))
      .addParameter(ParameterSpec(name = "value", type = this.typeRef.asTypeName()))
      .addCode(this.fields.writeCode())
      .build()
}

private fun CodeGenerationAst.ObjectType.writeFragmentDelegateToResponseFunSpec(): FunSpec {
  val fragmentRef = (this.kind as CodeGenerationAst.ObjectType.Kind.FragmentDelegate).fragmentTypeRef
  return FunSpec.builder("toResponse")
      .addModifiers(KModifier.OVERRIDE)
      .addParameter(ParameterSpec(name = "writer", type = JsonWriter::class.asTypeName()))
      .addParameter(ParameterSpec(name = "value", type = this.typeRef.asTypeName()))
      .addStatement("%T.toResponse(writer,·value.delegate)", fragmentRef.enclosingType!!.asAdapterTypeName())
      .build()
}

private fun CodeGenerationAst.ObjectType.writeFragmentAsInterfacesToResponseFunSpec(): FunSpec {
  val (defaultImplementation, possibleImplementations) = with(this.kind as CodeGenerationAst.ObjectType.Kind.ObjectWithFragments) {
    defaultImplementation to possibleImplementations
  }
  return FunSpec.builder("toResponse")
      .addModifiers(KModifier.OVERRIDE)
      .addParameter(ParameterSpec(name = "writer", type = JsonWriter::class.asTypeName()))
      .addParameter(ParameterSpec(name = "value", type = this.typeRef.asTypeName()))
      .applyIf(possibleImplementations.isEmpty()) {
        addCode(
            this@writeFragmentAsInterfacesToResponseFunSpec.fields.writeCode()
        )
      }
      .applyIf(possibleImplementations.isNotEmpty()) {
        beginControlFlow("when(value)")
        addCode(
            possibleImplementations.map { fragmentImplementation ->
              CodeBlock.of(
                  "is·%T·->·%L.toResponse(writer,·value)",
                  fragmentImplementation.typeRef.asTypeName(),
                  kotlinNameForTypeCaseAdapterField(fragmentImplementation.typeRef),
              )
            }.joinToCode(separator = "\n", suffix = "\n")
        )
        addStatement(
            "is·%T·->·${kotlinNameForTypeCaseAdapterField(defaultImplementation!!)}.toResponse(writer,·value)",
            defaultImplementation.asTypeName(),
        )
        endControlFlow()
      }
      .build()
}

private fun CodeGenerationAst.ObjectType.writeFragmentAsClassesToResponseFunSpec(): FunSpec {
  val writeFieldsCode = this.fields
      .map { field -> field.writeCode() }
      .joinToCode(separator = "\n")

  val possibleImplementations = (this.kind as CodeGenerationAst.ObjectType.Kind.ObjectWithFragments).possibleImplementations

  val writeFragmentsCode = possibleImplementations.map { fragmentImplementation ->
    val propertyName = fragmentImplementation.typeRef.fragmentVariableName()
    CodeBlock.of(
        "if (value.%L != null) %L.toResponse(writer, value.%L)",
        propertyName,
        fragmentImplementation.typeRef.fragmentResponseAdapterVariableName(),
        propertyName,
    )
  }.joinToCode(separator = "\n", suffix = "\n")

  return FunSpec.builder("toResponse")
      .addModifiers(KModifier.OVERRIDE)
      .addParameter(ParameterSpec(name = "writer", type = JsonWriter::class.asTypeName()))
      .addParameter(ParameterSpec(name = "value", type = this.typeRef.asTypeName()))
      .addStatement("writer.beginObject()")
      .addCode(writeFieldsCode)
      .addCode(writeFragmentsCode)
      .addStatement("writer.endObject()")
      .build()
}

internal fun List<CodeGenerationAst.Field>.writeCode(): CodeBlock {
  val builder = CodeBlock.builder()
  builder.addStatement("writer.beginObject()")
  forEach {
    builder.add(it.writeCode())
  }
  builder.addStatement("writer.endObject()")
  return builder.build()
}

private fun CodeGenerationAst.Field.writeCode(): CodeBlock {
  return CodeBlock.builder().apply {
    addStatement("writer.name(%S)", name)
    addStatement("${kotlinNameForAdapterField(type)}.toResponse(writer, value.${name.escapeKotlinReservedWord()})")
  }.build()
}