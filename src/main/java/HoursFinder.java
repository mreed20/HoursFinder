import io.javalin.Javalin;
import io.javalin.http.Context;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.stream.Collectors;

class HoursFinder
{
    // For getting all of our data
    private static DatabaseConnector dc = null;

    // Used for parsing in days of the week.
    private static final DateTimeFormatter dayOfWeekFormatter =
            DateTimeFormatter.ofPattern("E");

    public static void main(String[] args)
    {

        try {
            final String url = "jdbc:postgresql://localhost:5432/postgres";
            final String username = "postgres";
            final String password = "postgresqlmasterpassword";
            dc = new DatabaseConnector(url, username, password);
        } catch (SQLException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }

        Javalin app = Javalin.create(config ->
                // Tells server about our CSS files.
                config.addStaticFiles("/")
        ).start(7000);

        // Home page handler, which gets executed when we first visit the website
        // since we will be at the root directory ("/").
        app.get("/", ctx -> ctx.render(Paths.MUSTACHE_INDEX));


        // User login handler.
        app.post("/login", ctx -> {
                    try {
                        int gNumber = Integer.parseInt(ctx.formParam("gnumber"));
                        if (gNumber < 0 || gNumber > 99999999) {
                            throw new NumberFormatException();
                        }

                        Teacher teacher = dc.getTeacher(gNumber);
                        if (teacher != null) {
                            // Set a cookie on the client so that other pages know
                            // who is logged in, and thus what data to generate.
                            ctx.cookieStore("current_user", gNumber);
                            renderSelectClass(ctx, teacher);
                        } else {
                            ctx.render(Paths.MUSTACHE_INDEX, ErrorMaps.USER_NOT_FOUND);
                        }
                    } catch (NumberFormatException e) {
                        ctx.render(Paths.MUSTACHE_INDEX, ErrorMaps.INVALID_INPUT);
                    }
                }
        );

        // Class selection handler.
        app.post("/select_class", ctx -> {
                    String selection = ctx.formParam("class");
                    // This assertion should never trigger because the radio buttons in `select_class.mustache`
                    // have the `required` attribute, meaning that at least one radio button must be selected
                    // before the browser submits the form.
                    assert selection != null;
                    ctx.cookieStore("current_class", selection);
                    ctx.render(Paths.MUSTACHE_SELECT_AVAILABILITY);
                }
        );

        // Availability selection handler.
        app.post("/select_availability", ctx -> {
                    // Get provided schedule from the HTTP POST, which contains information about which
                    // boxes the user checked (the form parameter will be non-null if the box is checked).
                    List<DayOfWeek> daysAvailable = new ArrayList<>();
                    // These are the names of checkbox elements in select_availability.mustache.
                    String[] dayCheckboxes = new String[]{"Mon", "Tue", "Wed", "Thu", "Fri"};
                    for (String key : dayCheckboxes) {
                        if (ctx.formParam(key) != null) {
                            // The user said they are free on this day.
                            TemporalAccessor t = dayOfWeekFormatter.parse(key);
                            daysAvailable.add(DayOfWeek.from(t));
                        }
                    }

                    // Get office hour length.
                    int minutes = Integer.parseInt(Objects.requireNonNull(ctx.formParam("length"))
                            .split(" ")[0]);
                    assert minutes >= 30 && minutes <= 120;
                    Duration d = Duration.of(minutes, ChronoUnit.MINUTES);

                    // Finally generate the requisite time slots needed by ScheduleAnalyzer.
                    List<TimeSlot> timeSlots = HoursGenerator.genHours(d, daysAvailable);

                    // Get the name of the currently selected class, which we stored
                    // in a cookie previously.
                    String currentClassName = ctx.cookieStore("current_class");
                    assert currentClassName != null;

                    // Get the list of students in the class.
                    List<Student> students = new ArrayList<>(dc.getStudents(currentClassName));
                    // Generate the office hours.
                    ScheduleAnalyzer analyzer = new ScheduleAnalyzer(students, timeSlots);
                    List<GeneratedHour> hours = analyzer.buildGeneratedHours();
                    renderDisplayGeneratedHours(ctx, hours);
                }
        );

        app.post("/generate_again", ctx -> {
                    String selection = ctx.formParam("selection");
                    assert selection != null;
                    // Redirect to home page for now.
                    ctx.html(selection);
                }
        );

        app.before("/logout", ctx -> {
                    // Clear cookies.
                    ctx.clearCookieStore();
                    // Redirect to home page.
                    ctx.redirect("/logged_out.html");
                }
        );
    }

    private static void renderDisplayGeneratedHours(Context ctx, List<GeneratedHour> hours)
    {
        Map<String, Object> model = new HashMap<>();
        // Get the current class from the cookie store.
        // We use it as a caption for the table of generated hours.
        model.put("classname", ctx.cookieStore("current_class"));

        // TODO: test that no hours coincide with the class being selected
        hours.sort(Comparator.comparing(GeneratedHour::getAvailPercent).reversed());

        List<GeneratedHour> newList = hours.stream()
                                           .limit(5)
                                           .collect(Collectors.toList());
        model.put("hours", newList);

        ctx.render(Paths.MUSTACHE_DISPLAY_GENERATED_HOURS, model);
    }


    private static void renderSelectClass(Context ctx, Teacher teacher)
    {
        // Build a model which mustache will parse.
        Map<String, Object> model = new HashMap<>();
        model.put("username", teacher.name);
        model.put("classes", teacher.classesTaught);

        // Render the mustache file with the given model.
        ctx.render(Paths.MUSTACHE_SELECT_CLASS, model);
    }
}
