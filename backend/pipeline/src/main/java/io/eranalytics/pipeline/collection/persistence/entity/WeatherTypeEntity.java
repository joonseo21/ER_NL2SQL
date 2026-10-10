package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.collection.metadata.MetadataName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name="weather_types")
public class WeatherTypeEntity extends NamedMetadataEntity {
}

