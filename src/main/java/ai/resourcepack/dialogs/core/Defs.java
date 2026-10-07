package ai.resourcepack.dialogs.core;

import io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction;

import java.util.List;
import java.util.Map;

/** A dialog as written, before any placeholder is filled. Immutable; rebuilt on reload. */
final class Defs {

    private Defs() {}

    enum Type { AUTO, NOTICE, CONFIRMATION, MULTI_ACTION, LINKS }

    record ForEach(String list, String alias, int perPage, int limit) {}

    record DialogDef(
            String id,
            String namespace,
            String source,
            String title,
            String externalTitle,
            Type type,
            int columns,
            boolean escape,
            boolean pause,
            DialogAfterAction after,
            List<BodyDef> body,
            List<InputDef> inputs,
            List<ButtonDef> buttons,
            ButtonDef exit,
            Map<String, List<Map<String, String>>> lists,
            String command,
            List<String> aliases,
            String permission,
            List<String> args,
            String ifMissing,
            String json,
            Map<String, String> defaults) {
    }

    enum BodyKind { TEXT, ITEM }

    record BodyDef(
            BodyKind kind,
            String text,
            int width,
            String item,
            String head,
            String name,
            List<String> lore,
            String model,
            int amount,
            boolean glow,
            boolean tooltip,
            boolean decorations,
            int itemWidth,
            int itemHeight,
            String description,
            String condition,
            ForEach forEach) {
    }

    enum InputKind { TEXT, OPTIONS, TOGGLE, SLIDER }

    record OptionDef(String id, String text, String initial) {}

    record InputDef(
            String key,
            InputKind kind,
            String label,
            boolean labelVisible,
            int width,
            String initial,
            int maxLength,
            int lines,
            int height,
            List<OptionDef> options,
            ForEach optionsForEach,
            OptionDef optionTemplate,
            String onTrue,
            String onFalse,
            float min,
            float max,
            Float step,
            String format,
            boolean required,
            int minLength,
            String pattern,
            String error,
            String condition) {
    }

    record ButtonDef(
            String id,
            String label,
            String tooltip,
            int width,
            String permission,
            String condition,
            ForEach forEach,
            List<String> actions,
            boolean validate) {
    }
}
