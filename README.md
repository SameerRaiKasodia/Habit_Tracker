# Habit Tracker Pro 🔥

A full-stack Habit Tracker web application built using **Java** (HttpServer) and **MySQL**.

## Features
- Add/Delete Good and Bad Habits.
- 30-Day visual heatmap.
- Daily streaks and 30-day completion percentage.
- Optional daily notes.
- Dark Mode toggle.
- Background timer for daily task execution.
- MySQL database for persistent storage.

## Tech Stack
- **Backend:** Java (JDK 17), `com.sun.net.httpserver`
- **Database:** MySQL (JDBC)
- **Frontend:** HTML, CSS, Vanilla JavaScript

## How to Run
1. Create a MySQL database named `habit_tracker_db` and run the SQL scripts provided.
2. Add the MySQL Connector/J library in IntelliJ.
3. Open `src/HabitTracker.java`, update the `DB_PASS` variable to your MySQL password.
4. Run the `main` method in `HabitTracker.java`.
5. Open `http://localhost:8080` in your browser.