package io.eranalytics.pipeline.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "skill_groups")
public class SkillGroupEntity extends NamedMetadataEntity {}
