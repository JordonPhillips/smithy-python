/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.python.codegen;

import java.util.ArrayList;
import java.util.Set;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.StringTrait;
import software.amazon.smithy.python.codegen.generators.MemberDeserializerGenerator;
import software.amazon.smithy.python.codegen.generators.MemberSerializerGenerator;

/**
 * Renders unions.
 */
final class UnionGenerator implements Runnable {

    private final GenerationContext context;
    private final Model model;
    private final SymbolProvider symbolProvider;
    private final PythonWriter writer;
    private final UnionShape shape;
    private final Set<Shape> recursiveShapes;

    UnionGenerator(
            GenerationContext context,
            PythonWriter writer,
            UnionShape shape,
            Set<Shape>  recursiveShapes
    ) {
        this.context = context;
        this.model = context.model();
        this.symbolProvider = context.symbolProvider();
        this.writer = writer;
        this.shape = shape;
        this.recursiveShapes = recursiveShapes;
    }

    @Override
    public void run() {
        writer.pushState();
        var parentName = symbolProvider.toSymbol(shape).getName();
        writer.addStdlibImport("dataclasses", "dataclass");
        writer.addImport("smithy_core.serializers", "ShapeSerializer");
        var schemaSymbol = symbolProvider.toSymbol(shape).expectProperty(SymbolProperties.SCHEMA);
        writer.putContext("schema", schemaSymbol);

        var memberNames = new ArrayList<String>();
        for (MemberShape member : shape.members()) {
            var memberSymbol = symbolProvider.toSymbol(member);
            memberNames.add(memberSymbol.getName());

            var target = model.expectShape(member.getTarget());
            var targetSymbol = symbolProvider.toSymbol(target);

            writer.write("""
                    @dataclass
                    class $L:
                        ${C|}

                        value: $T

                        def serialize(self, serializer: ShapeSerializer):
                            serializer.write_struct($T, self)

                        def serialize_members(self, serializer: ShapeSerializer):
                            ${C|}

                    """,
                    memberSymbol.getName(),
                    writer.consumer(w -> member.getMemberTrait(model, DocumentationTrait.class)
                            .map(StringTrait::getValue).ifPresent(w::writeDocs)),
                    targetSymbol,
                    schemaSymbol,
                    writer.consumer(w -> target.accept(
                            new MemberSerializerGenerator(context, w, member, "serializer"))));
        }

        // Note that the unknown variant doesn't implement __eq__. This is because
        // the default implementation does exactly what we want: an instance check.
        // Since the underlying value is unknown and un-comparable, that is the only
        // realistic implementation.
        var unknownSymbol = symbolProvider.toSymbol(shape).expectProperty(SymbolProperties.UNION_UNKNOWN);
        writer.addImport("smithy_core.exceptions", "SmithyException");
        writer.write("""
                @dataclass
                class $1L:
                    \"""Represents an unknown variant.

                    If you receive this value, you will need to update your library to receive the
                    parsed value.

                    This value may not be deliberately sent.
                    \"""

                    tag: str

                    def serialize(self, serializer: ShapeSerializer):
                        raise SmithyException("Unknown union variants may not be serialized.")

                    def serialize_members(self, serializer: ShapeSerializer):
                        raise SmithyException("Unknown union variants may not be serialized.")

                """, unknownSymbol.getName());
        memberNames.add(unknownSymbol.getName());

        shape.getTrait(DocumentationTrait.class).ifPresent(trait -> writer.writeComment(trait.getValue()));
        writer.write("type $L = $L\n", parentName, String.join(" | ", memberNames));

        generateDeserializer();
        writer.popState();
    }

    private void generateDeserializer() {
        writer.addLogger();
        writer.addStdlibImports("typing", Set.of("Self", "Any"));
        writer.addImport("smithy_core.deserializers", "ShapeDeserializer");
        writer.addImport("smithy_core.exceptions", "SmithyException");

        // TODO: add in unknown handling

        var symbol = symbolProvider.toSymbol(shape);
        var deserializerSymbol = symbol.expectProperty(SymbolProperties.DESERIALIZER);
        var schemaSymbol = symbol.expectProperty(SymbolProperties.SCHEMA);
        writer.putContext("schema", schemaSymbol);
        writer.write("""
                class $1L:
                    _result: $2T | None = None

                    def deserialize(self, deserializer: ShapeDeserializer) -> $2T:
                        self._result = None
                        deserializer.read_struct($3T, self._consumer)

                        if self._result is None:
                            raise SmithyException("Unions must have exactly one value, but found none.")

                        return self._result

                    def _consumer(self, schema: Schema, de: ShapeDeserializer) -> None:
                        match schema.expect_member_index():
                            ${4C|}
                            case _:
                                logger.debug(f"Unexpected member schema: {schema}")

                    def _set_result(self, value: $2T) -> None:
                        if self._result is not None:
                            raise SmithyException("Unions must have exactly one value, but found more than one.")
                        self._result = value
                """,
                deserializerSymbol.getName(),
                symbol,
                schemaSymbol,
                writer.consumer(w -> deserializeMembers()));
    }

    private void deserializeMembers() {
        int index = 0;
        for (MemberShape member : shape.members()) {
            var target = model.expectShape(member.getTarget());
            writer.write("""
                    case $L:
                        self._set_result($T(${C|}))
                    """, index++, symbolProvider.toSymbol(member), writer.consumer(w ->
                    target.accept(new MemberDeserializerGenerator(context, writer, member, "de"))
            ));
        }
    }
}
