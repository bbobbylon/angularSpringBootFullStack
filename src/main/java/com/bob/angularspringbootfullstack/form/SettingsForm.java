package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SettingsForm {
    @NotNull(message = "Enabled checkbox cannot be empty")
    private Boolean enabled;
    @NotNull(message = "notLocked checkbox cannot be empty")
    private Boolean notLocked;

}
