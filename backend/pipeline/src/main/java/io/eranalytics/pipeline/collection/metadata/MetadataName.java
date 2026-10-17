package io.eranalytics.pipeline.collection.metadata;

public record MetadataName(
        int code, String nameKo, String source, String l10nKey, Details details) {
    public MetadataName {
        if (code <= 0 || nameKo == null || nameKo.isBlank() || source == null || l10nKey == null) {
            throw new IllegalArgumentException("Invalid name metadata");
        }
    }

    public record Details(
            String type,
            String grade,
            String gradeKo,
            String equipmentType,
            Integer groupCode,
            String traitGroup,
            Boolean active,
            Integer makeMaterial1,
            Integer makeMaterial2) {
        public Details(
                String type,
                String grade,
                String gradeKo,
                String equipmentType,
                Integer groupCode,
                String traitGroup,
                Boolean active) {
            this(type, grade, gradeKo, equipmentType, groupCode, traitGroup, active, null, null);
        }

        public static final Details EMPTY = new Details(null, null, null, null, null, null, null);
    }
}
