package io.eranalytics.pipeline.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "weather_types")
public class WeatherTypeEntity extends NamedMetadataEntity {}
