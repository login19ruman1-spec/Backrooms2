package ru.backrooms;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.Random;

final class Archive {

    private Archive() {
    }

    static ItemStack book(boolean antivirus, int volume) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();

        String series = antivirus ? "Антивирус" : "Драйверы";

        meta.setTitle(series + " / " + volume);
        meta.setAuthor("Отдел отсутствующих помещений");

        Random random = new Random(
                volume * 918273L + (antivirus ? 47 : 11));

        String[] modules = {
                "wall", "door", "visitor", "memory",
                "corridor", "pool", "archive", "surface"
        };

        String[] notices = antivirus
                ? new String[]{
                    "Оригинал посетителя не обнаружен.",
                    "Не удаляйте стену, если она помнит ваше имя.",
                    "Копия прошла проверку. Проверяющий — нет.",
                    "Комната считается чистой, пока дверь закрыта.",
                    "Сигнатура совпала с завтрашним журналом.",
                    "Изоляция выполнена с внешней стороны.",
                    "Антивирус не должен смотреть в бассейн.",
                    "После очистки верните отсутствующие блоки."
                }
                : new String[]{
                    "Драйвер стены загружен до создания стены.",
                    "Устройство CORRIDOR отвечает из другого уровня.",
                    "Не обновляйте посетителя во время сна.",
                    "Для выхода требуется предыдущая версия двери.",
                    "Память комнаты превышает её объём.",
                    "Откат планировки выполнен вперёд.",
                    "Найден второй экземпляр вашего имени.",
                    "Подпись изготовителя находится за обоями."
                };

        for (int page = 0; page < 8; page++) {
            StringBuilder text = new StringBuilder();

            text.append(series)
                    .append(" / том ")
                    .append(volume)
                    .append("\n\n")
                    .append(notices[page])
                    .append("\n\n");

            for (int line = 0; line < 10; line++) {
                String module =
                        modules[random.nextInt(modules.length)];

                String code = String.format(
                        "%04X", random.nextInt(65536));

                int value = random.nextInt(256);

                text.append(antivirus ? "scan." : "drv.")
                        .append(module)
                        .append("[")
                        .append(code)
                        .append("]=")
                        .append(value)
                        .append(";\n");
            }

            text.append("\n// ")
                    .append(antivirus
                            ? "исключение: наблюдатель"
                            : "перезапуск не завершает коридор");

            meta.addPage(text.toString());
        }

        item.setItemMeta(meta);
        return item;
    }
}
