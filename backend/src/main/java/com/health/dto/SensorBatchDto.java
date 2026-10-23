package com.health.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record SensorBatchDto(
		@NotEmpty @Valid List<ReadingDto> readings) {

	public SensorBatchDto {
		readings = List.copyOf(readings);
	}
}
