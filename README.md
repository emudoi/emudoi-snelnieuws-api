# SnelNieuws API

A Scala-based REST API built with Scalatra and Doobie, designed to replace the News API for the SnelNieuws iOS app.

## Tech Stack

- **Scala 2.13** - Programming language
- **Scalatra** - Web framework
- **Doobie** - Database access library
- **PostgreSQL** - Database
- **Jetty** - Embedded web server
- **Docker** - Containerization

## API Endpoints

### News API Compatible Endpoints

These endpoints match the News API format that the iOS app expects:

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/v2/everything?q={query}` | Search articles by query |
| GET | `/v2/top-headlines?category={category}` | Get articles by category |

### Additional Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/articles/:id` | Get single article |
| POST | `/articles` | Create new article |
| DELETE | `/articles/:id` | Delete article |
| GET | `/health` | Health check |

## Response Format

```json
{
  "status": "ok",
  "totalResults": 10,
  "articles": [
    {
      "id": 1,
      "author": "John Smith",
      "title": "Article Title",
      "description": "Short description",
      "url": "https://example.com/article",
      "urlToImage": "https://example.com/image.jpg",
      "publishedAt": "2024-03-21T10:30:00Z",
      "content": "Full article content...",
      "category": "Trending"
    }
  ]
}
```

## Running with Docker

### Quick Start

```bash
# Start both PostgreSQL and the API
docker-compose up -d

# Check logs
docker-compose logs -f api

# Stop services
docker-compose down
```

The API will be available at `http://localhost:8080`

### Build Only

```bash
# Build the Docker image
docker build -t snelnieuws-api .
```

## Running Locally (Development)

### Prerequisites

- JDK 17+
- SBT 1.9+
- PostgreSQL 15+

### Setup Database

```bash
# Create database
createdb snelnieuws

# Run init script
psql -d snelnieuws -f init.sql
```

### Run the API

```bash
# Navigate to project directory
cd SnelNieuwsApi

# Run with SBT
sbt run
```

### Build Fat JAR

```bash
sbt assembly
java -jar target/scala-2.13/snelnieuws-api.jar
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| PORT | 8080 | Server port |
| DATABASE_URL | jdbc:postgresql://localhost:5432/snelnieuws | Database URL |
| DATABASE_USER | postgres | Database username |
| DATABASE_PASSWORD | postgres | Database password |

## Updating the iOS App

To use this API instead of News API, update `NewsAPIService.swift`:

```swift
// Change from:
static let baseURL = "https://newsapi.org/v2/"

// To:
static let baseURL = "http://localhost:8080/"
```

For production, replace `localhost:8080` with your deployed API URL.

## Categories

The app supports these categories:
- Trending
- Health
- Sports
- Business

## Creating Articles

```bash
curl -X POST http://localhost:8080/articles \
  -H "Content-Type: application/json" \
  -d '{
    "author": "Your Name",
    "title": "Article Title",
    "description": "Short description",
    "url": "https://example.com/article",
    "urlToImage": "https://example.com/image.jpg",
    "content": "Full article content here...",
    "category": "Trending"
  }'
```
