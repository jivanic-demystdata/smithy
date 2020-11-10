/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.model.validation.validators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.pattern.SmithyPattern;
import software.amazon.smithy.model.pattern.SmithyPattern.Segment;
import software.amazon.smithy.model.pattern.UriPattern;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.EndpointTrait;
import software.amazon.smithy.model.traits.HostLabelTrait;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.model.traits.PatternTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.utils.OptionalUtils;
import software.amazon.smithy.utils.Pair;

/**
 * Validates that no two URIs in a service conflict with each other.
 */
public final class HttpUriConflictValidator extends AbstractValidator {

    @Override
    public List<ValidationEvent> validate(Model model) {
        if (!model.isTraitApplied(HttpTrait.class)) {
            return Collections.emptyList();
        }

        return model.shapes(ServiceShape.class)
                .flatMap(shape -> validateService(model, shape).stream())
                .collect(Collectors.toList());
    }

    private List<ValidationEvent> validateService(Model model, ServiceShape service) {
        List<OperationShape> operations = TopDownIndex.of(model).getContainedOperations(service)
                .stream()
                .filter(shape -> shape.getTrait(HttpTrait.class).isPresent())
                .collect(Collectors.toList());
        return operations.stream()
                .flatMap(shape -> Trait.flatMapStream(shape, HttpTrait.class))
                .flatMap(pair -> checkConflicts(model, pair, operations).stream())
                .collect(Collectors.toList());
    }

    private List<ValidationEvent> checkConflicts(
            Model model,
            Pair<OperationShape, HttpTrait> pair,
            List<OperationShape> operations
    ) {
        OperationShape operation = pair.getLeft();
        String method = pair.getRight().getMethod();
        UriPattern pattern = pair.getRight().getUri();

        // Some conflicts are potentially allowable, so we split them up into to lists.
        List<Pair<ShapeId, UriPattern>> conflicts = new ArrayList<>();
        List<Pair<ShapeId, UriPattern>> allowableConflicts = new ArrayList<>();

        operations.stream()
                .filter(shape -> shape != operation)
                .flatMap(shape -> Trait.flatMapStream(shape, HttpTrait.class))
                .filter(other -> other.getRight().getMethod().equals(method))
                .filter(other -> other.getRight().getUri().conflictsWith(pattern))
                .filter(other -> endpointConflicts(model, operation, other.getLeft()))
                .forEach(other -> {
                    // Now that we know we have a conflict, determine whether it is allowable or not.
                    if (isAllowableConflict(model, operation, other.getLeft())) {
                        allowableConflicts.add(Pair.of(other.getLeft().getId(), other.getRight().getUri()));
                    } else {
                        conflicts.add(Pair.of(other.getLeft().getId(), other.getRight().getUri()));
                    }
                });

        // Non-allowable conflicts get turned into ERRORs, they must be resolved to pass validation.
        List<ValidationEvent> events = new ArrayList<>();
        if (!conflicts.isEmpty()) {
            events.add(error(operation, formatConflicts(pattern, conflicts)));
        }

        // Allowable conflicts get turned into DANGERs, which must at least be acknowledged with a suppression.
        if (!allowableConflicts.isEmpty()) {
            String message = formatConflicts(pattern, allowableConflicts)
                    + ". Pattern traits applied to the label members prevent the label value from evaluating to a "
                    + "conflict, but this is still a poor design. If this is acceptable, this can be suppressed.";
            events.add(danger(operation, message));
        }

        return events;
    }

    private boolean endpointConflicts(Model model, OperationShape operation, OperationShape otherOperation) {
        // If neither operation has the endpoint trait, then their endpoints do conflict.
        if (!operation.hasTrait(EndpointTrait.ID) && !otherOperation.hasTrait(EndpointTrait.ID)) {
            return true;
        }

        // If one, but not both, operations have the endpoint trait, then their endpoints can't conflict.
        if (operation.hasTrait(EndpointTrait.ID) ^ otherOperation.hasTrait(EndpointTrait.ID)) {
            return false;
        }

        // At this point we know both operations have the endpoint trait, so we need to check if
        // they conflict.
        SmithyPattern prefix = operation.getTrait(EndpointTrait.class).get().getHostPrefix();
        SmithyPattern otherPrefix = otherOperation.getTrait(EndpointTrait.class).get().getHostPrefix();
        boolean allowable = !isAllowableConflict(
                model, prefix, operation, otherPrefix, otherOperation, this::getHostLabelPatterns);
        return allowable;
    }

    private boolean isAllowableConflict(Model model, OperationShape operation, OperationShape otherOperation) {
        UriPattern uriPattern = operation.getTrait(HttpTrait.class).get().getUri();
        UriPattern otherUriPattern = otherOperation.getTrait(HttpTrait.class).get().getUri();
        return isAllowableConflict(
                model, uriPattern, operation, otherUriPattern, otherOperation, this::getHttpLabelPatterns);
    }

    private boolean isAllowableConflict(
            Model model,
            SmithyPattern pattern,
            OperationShape operation,
            SmithyPattern otherPattern,
            OperationShape otherOperation,
            BiFunction<Model, OperationShape, Map<String, Pattern>> getLabelPatterns
    ) {

        Map<Segment, Segment> conflictingLabelSegments = pattern.getConflictingLabelSegments(otherPattern);

        // If there aren't any conflicting label segments that means the uris are identical, which is not allowable.
        if (conflictingLabelSegments.isEmpty()) {
            return false;
        }

        Map<String, Pattern> labelPatterns = getLabelPatterns.apply(model, operation);
        Map<String, Pattern> otherLabelPatterns = getLabelPatterns.apply(model, otherOperation);

        return conflictingLabelSegments.entrySet().stream()
                // Only allow conflicts in cases where one of the segments is static and the other is a label.
                .filter(conflict -> conflict.getKey().isLabel() != conflict.getValue().isLabel())
                // Only allow the uris to conflict if every conflicting segment is allowable.
                .allMatch(conflict -> {
                    Pattern p;
                    String staticSegment;

                    if (conflict.getKey().isLabel()) {
                        p = labelPatterns.get(conflict.getKey().getContent());
                        staticSegment = conflict.getValue().getContent();
                    } else {
                        p = otherLabelPatterns.get(conflict.getValue().getContent());
                        staticSegment = conflict.getKey().getContent();
                    }

                    if (p == null) {
                        return false;
                    }

                    // If the pattern on the label segment does not match the static segment, then this segment's
                    // conflict is allowable.
                    return !p.matcher(staticSegment).find();
                });
    }

    private Map<String, Pattern> getHttpLabelPatterns(Model model, OperationShape operation) {
        return HttpBindingIndex.of(model)
                .getRequestBindings(operation).entrySet().stream()
                .filter(entry -> entry.getValue().getLocation().equals(HttpBinding.Location.LABEL))
                .flatMap(entry -> OptionalUtils.stream(entry.getValue()
                        .getMember().getMemberTrait(model, PatternTrait.class)
                        .map(pattern -> Pair.of(entry.getKey(), pattern.getPattern()))))
                .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
    }

    private Map<String, Pattern> getHostLabelPatterns(Model model, OperationShape operation) {
        Optional<StructureShape> input = OperationIndex.of(model).getInput(operation);
        if (!input.isPresent()) {
            return Collections.emptyMap();
        }
        return input.get().members().stream()
                .filter(member -> member.hasTrait(HostLabelTrait.ID))
                .flatMap(member -> Trait.flatMapStream(member, PatternTrait.class))
                .collect(Collectors.toMap(
                        pair -> pair.getLeft().getMemberName(), pair -> pair.getRight().getPattern()));
    }

    private String formatConflicts(UriPattern pattern, List<Pair<ShapeId, UriPattern>> conflicts) {
        String conflictString = conflicts.stream()
                .map(conflict -> String.format("`%s` (%s)", conflict.getLeft(), conflict.getRight()))
                .sorted()
                .collect(Collectors.joining(", "));

        return String.format(
                "Operation URI, `%s`, conflicts with other operation URIs in the same service: [%s]",
                pattern, conflictString);
    }
}
