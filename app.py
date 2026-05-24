from io import BytesIO
from pathlib import Path

import altair as alt
import pandas as pd
import streamlit as st

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaIoBaseDownload


DATETIME_FORMAT = "%Y.%m.%d %H:%M:%S"
SCOPES = ["https://www.googleapis.com/auth/drive.readonly"]

HEART_RATE_DEFAULT_PATH = Path("/Users/abhigyanshekhar/Downloads/Heart rate 2026.05.11 Samsung Health.csv")
STEPS_DEFAULT_PATH = Path("/Users/abhigyanshekhar/Downloads/Steps 19-2026 Samsung Health.csv")
GOOGLE_CREDENTIALS_PATH = Path("credentials.json")
GOOGLE_TOKEN_PATH = Path("token.json")

HEART_RATE_DRIVE_QUERY = "name contains 'Heart rate' and name contains 'Samsung Health' and trashed = false"
STEPS_DRIVE_QUERY = "name contains 'Steps' and name contains 'Samsung Health' and trashed = false"


st.set_page_config(page_title="Samsung Health Dashboard", layout="wide")


def get_drive_credentials() -> Credentials:
    creds = None

    if GOOGLE_TOKEN_PATH.exists():
        creds = Credentials.from_authorized_user_file(str(GOOGLE_TOKEN_PATH), SCOPES)

    if creds and creds.valid:
        return creds

    if creds and creds.expired and creds.refresh_token:
        creds.refresh(Request())
        GOOGLE_TOKEN_PATH.write_text(creds.to_json(), encoding="utf-8")
        return creds

    if not GOOGLE_CREDENTIALS_PATH.exists():
        raise FileNotFoundError(
            "Google Drive credentials were not found. Add a desktop OAuth client file named `credentials.json` "
            "next to `app.py`."
        )

    flow = InstalledAppFlow.from_client_secrets_file(str(GOOGLE_CREDENTIALS_PATH), SCOPES)
    creds = flow.run_local_server(port=0)
    GOOGLE_TOKEN_PATH.write_text(creds.to_json(), encoding="utf-8")
    return creds


@st.cache_resource(show_spinner=False)
def get_drive_service():
    creds = get_drive_credentials()
    return build("drive", "v3", credentials=creds)


@st.cache_data(show_spinner=False)
def fetch_latest_drive_file(query: str) -> tuple[str, bytes]:
    service = get_drive_service()

    response = (
        service.files()
        .list(
            q=query,
            pageSize=1,
            orderBy="modifiedTime desc",
            fields="files(id, name, modifiedTime)",
            supportsAllDrives=True,
            includeItemsFromAllDrives=True,
        )
        .execute()
    )

    files = response.get("files", [])
    if not files:
        raise FileNotFoundError(f"No Google Drive files matched query: {query}")

    file_id = files[0]["id"]
    file_name = files[0]["name"]
    request = service.files().get_media(fileId=file_id, supportsAllDrives=True)
    buffer = BytesIO()
    downloader = MediaIoBaseDownload(buffer, request)

    done = False
    while not done:
        _, done = downloader.next_chunk()

    return file_name, buffer.getvalue()


@st.cache_data
def load_timeseries_data(file_source, value_column: str) -> pd.DataFrame:
    df = pd.read_csv(file_source, encoding="utf-8-sig")
    required_columns = ["Date", value_column]
    missing_columns = [column for column in required_columns if column not in df.columns]

    if missing_columns:
        missing = ", ".join(missing_columns)
        raise ValueError(f"Missing required column(s): {missing}")

    df["Date"] = pd.to_datetime(df["Date"], format=DATETIME_FORMAT, errors="coerce")
    df[value_column] = pd.to_numeric(df[value_column], errors="coerce")
    df = df.dropna(subset=["Date", value_column]).copy()

    if df.empty:
        raise ValueError(f"No valid rows were found for {value_column}.")

    df[value_column] = df[value_column].astype(int)
    return df.sort_values("Date").set_index("Date")


def build_line_chart(df: pd.DataFrame, value_column: str, title: str, color: str) -> alt.Chart:
    chart_data = df.reset_index()
    return (
        alt.Chart(chart_data)
        .mark_line(color=color, point=True)
        .encode(
            x=alt.X("Date:T", title="Time"),
            y=alt.Y(f"{value_column}:Q", title=title),
            tooltip=[
                alt.Tooltip("Date:T", title="Timestamp"),
                alt.Tooltip(f"{value_column}:Q", title=title),
            ],
        )
        .properties(height=360)
        .interactive()
    )


def resolve_source(uploaded_file, use_drive: bool, use_local_file: bool, local_path: Path, drive_query: str):
    if uploaded_file is not None:
        return uploaded_file, "uploaded file"

    if use_drive:
        file_name, file_bytes = fetch_latest_drive_file(drive_query)
        return BytesIO(file_bytes), f"Google Drive: {file_name}"

    if use_local_file:
        return local_path, f"local file: {local_path.name}"

    return None, None


st.title("Samsung Health Dashboard")
st.caption("Time-series view of heart rate and steps from Samsung Health exports or the latest matching files in Google Drive.")

st.sidebar.header("Data Source")
heart_rate_upload = st.sidebar.file_uploader("Upload heart rate CSV", type=["csv"], key="heart_rate_file")
steps_upload = st.sidebar.file_uploader("Upload steps CSV", type=["csv"], key="steps_file")
use_google_drive = st.sidebar.toggle("Use Google Drive latest files", value=True)
use_local_files = st.sidebar.toggle("Fallback to detected local files", value=True)

if use_google_drive:
    st.sidebar.caption(
        "Requires `credentials.json` from a Google Cloud desktop OAuth client and Drive API access. "
        "The first run opens a browser consent flow and stores `token.json` locally."
    )

heart_rate_df = None
steps_df = None
heart_rate_source_label = None
steps_source_label = None

try:
    heart_rate_source, heart_rate_source_label = resolve_source(
        heart_rate_upload,
        use_google_drive,
        use_local_files,
        HEART_RATE_DEFAULT_PATH,
        HEART_RATE_DRIVE_QUERY,
    )
    if heart_rate_source is not None:
        heart_rate_df = load_timeseries_data(heart_rate_source, "Heart rate")
except FileNotFoundError as exc:
    st.error(f"Heart rate source unavailable: {exc}")
except HttpError as exc:
    st.error(f"Google Drive error while loading heart rate data: {exc}")
except Exception as exc:
    st.error(f"Unable to load the heart rate CSV: {exc}")

try:
    steps_source, steps_source_label = resolve_source(
        steps_upload,
        use_google_drive,
        use_local_files,
        STEPS_DEFAULT_PATH,
        STEPS_DRIVE_QUERY,
    )
    if steps_source is not None:
        steps_df = load_timeseries_data(steps_source, "Steps")
except FileNotFoundError as exc:
    st.error(f"Steps source unavailable: {exc}")
except HttpError as exc:
    st.error(f"Google Drive error while loading steps data: {exc}")
except Exception as exc:
    st.error(f"Unable to load the steps CSV: {exc}")

if heart_rate_df is None and steps_df is None:
    st.info("No data source could be loaded. Upload files, enable local fallback, or configure Google Drive access.")
else:
    status_columns = st.columns(2)
    if heart_rate_df is not None:
        status_columns[0].success(f"Heart rate source: {heart_rate_source_label}")
    if steps_df is not None:
        status_columns[1].success(f"Steps source: {steps_source_label}")

    if heart_rate_df is not None:
        st.subheader("Heart Rate")
        metric_col_1, metric_col_2, metric_col_3 = st.columns(3)
        metric_col_1.metric("Average BPM", f"{heart_rate_df['Heart rate'].mean():.1f}")
        metric_col_2.metric("Maximum BPM", f"{int(heart_rate_df['Heart rate'].max())}")
        metric_col_3.metric("Minimum BPM", f"{int(heart_rate_df['Heart rate'].min())}")
        st.altair_chart(
            build_line_chart(heart_rate_df, "Heart rate", "Heart Rate (BPM)", "#d9485f"),
            use_container_width=True,
        )

    if steps_df is not None:
        st.subheader("Steps")
        metric_col_1, metric_col_2, metric_col_3 = st.columns(3)
        metric_col_1.metric("Total Steps", f"{int(steps_df['Steps'].sum()):,}")
        metric_col_2.metric("Average Steps / Entry", f"{steps_df['Steps'].mean():.1f}")
        metric_col_3.metric("Peak Steps / Entry", f"{int(steps_df['Steps'].max())}")
        st.altair_chart(
            build_line_chart(steps_df, "Steps", "Steps", "#2563eb"),
            use_container_width=True,
        )

    if heart_rate_df is not None or steps_df is not None:
        st.subheader("Raw Data")
        if heart_rate_df is not None:
            with st.expander("View heart rate data"):
                st.dataframe(heart_rate_df, use_container_width=True)
        if steps_df is not None:
            with st.expander("View steps data"):
                st.dataframe(steps_df, use_container_width=True)
